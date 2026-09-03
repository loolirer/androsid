import json
import socket
import struct
import threading

import rclpy
from builtin_interfaces.msg import Time
from rclpy.node import Node
from rclpy.qos import QoSPresetProfiles
from sensor_msgs.msg import BatteryState, CompressedImage, Imu, MagneticField, NavSatFix, NavSatStatus
from std_msgs.msg import Bool, Int32

TYPE_JSON = 0x01
TYPE_FRAME = 0x02


# Android device frame -> ROS REP-103 FLU (landscape orientation)
def android_to_flu(x, y, z):
    return -z, y, x


def to_ros_time(nanos):
    return Time(sec=int(nanos // 1_000_000_000), nanosec=int(nanos % 1_000_000_000))

# BatteryManager.BATTERY_STATUS_* -> ROS 2 BatteryState.POWER_SUPPLY_STATUS_*
ANDROID_STATUS_TO_ROS = {
    1: BatteryState.POWER_SUPPLY_STATUS_UNKNOWN,      # BATTERY_STATUS_UNKNOWN
    2: BatteryState.POWER_SUPPLY_STATUS_CHARGING,     # BATTERY_STATUS_CHARGING
    3: BatteryState.POWER_SUPPLY_STATUS_DISCHARGING,  # BATTERY_STATUS_DISCHARGING
    4: BatteryState.POWER_SUPPLY_STATUS_NOT_CHARGING, # BATTERY_STATUS_NOT_CHARGING
    5: BatteryState.POWER_SUPPLY_STATUS_FULL,         # BATTERY_STATUS_FULL
}

# Android BatteryManager.BATTERY_HEALTH_* -> ROS 2 BatteryState.POWER_SUPPLY_HEALTH_*
ANDROID_HEALTH_TO_ROS = {
    1: BatteryState.POWER_SUPPLY_HEALTH_UNKNOWN,              # BATTERY_HEALTH_UNKNOWN
    2: BatteryState.POWER_SUPPLY_HEALTH_GOOD,                 # BATTERY_HEALTH_GOOD
    3: BatteryState.POWER_SUPPLY_HEALTH_OVERHEAT,             # BATTERY_HEALTH_OVERHEAT
    4: BatteryState.POWER_SUPPLY_HEALTH_DEAD,                 # BATTERY_HEALTH_DEAD
    5: BatteryState.POWER_SUPPLY_HEALTH_OVERVOLTAGE,          # BATTERY_HEALTH_OVERVOLTAGE
    6: BatteryState.POWER_SUPPLY_HEALTH_UNSPEC_FAILURE,  # BATTERY_HEALTH_UNSPECIFIED_FAILURE
    7: BatteryState.POWER_SUPPLY_HEALTH_COLD,                 # BATTERY_HEALTH_COLD
}


class MobileSensors(Node):

    def __init__(self):
        super().__init__("mobile_sensors")

        self.declare_parameter("host", "127.0.0.1")
        self.declare_parameter("port", 9870)
        self.declare_parameter("imu_frame", "imu_link")
        self.declare_parameter("gps_frame", "gps_link")
        self.declare_parameter("camera_frame", "camera_optical_frame")

        self.host = self.get_parameter("host").value
        self.port = self.get_parameter("port").value
        self.imu_frame = self.get_parameter("imu_frame").value
        self.gps_frame = self.get_parameter("gps_frame").value
        self.camera_frame = self.get_parameter("camera_frame").value

        sensor_qos = QoSPresetProfiles.SENSOR_DATA.value
        self.pub_imu = self.create_publisher(Imu, "imu/data_raw", sensor_qos)
        self.pub_mag = self.create_publisher(MagneticField, "imu/mag", sensor_qos)
        self.pub_gps = self.create_publisher(NavSatFix, "gps/fix", sensor_qos)
        self.pub_img = self.create_publisher(
            CompressedImage, "camera/image_raw/compressed", sensor_qos
        )
        self.pub_battery = self.create_publisher(
            BatteryState, "battery/state", sensor_qos
        )

        self.sub_torch = self.create_subscription(
            Bool,
            '/flashlight/cmd',
            self._on_torch_cmd,
            10
        )

        self.sub_vibrate = self.create_subscription(
            Int32,
            '/vibrate/cmd',
            self._on_vibrate_cmd,
            10
        )

        self._last_accel = None
        self._logged_provider = False

        self._stop = threading.Event()
        self._sock = None
        self._thread = threading.Thread(target=self._run, daemon=True)
        self._thread.start()

    def destroy_node(self):
        self._stop.set()

        sock, self._sock = self._sock, None
        if sock is not None:
            try:
                sock.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass

        self._thread.join(timeout=2.0)
        if self._thread.is_alive():
            self.get_logger().warn("Reader thread still running after 2s")

        return super().destroy_node()

    def _run(self):
        while not self._stop.is_set() and rclpy.ok():
            try:
                self.get_logger().info(f"Connecting to {self.host}:{self.port}")
                with socket.create_connection(
                    (self.host, self.port), timeout=10
                ) as sock:
                    sock.settimeout(None)
                    sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                    self._sock = sock
                    self.get_logger().info("Connected!")
                    self._consume(sock)

            except OSError as exc:
                if self._stop.is_set():
                    break
                self.get_logger().warn(f"Connection failed: {exc}; retrying in 2s")
                self._stop.wait(2.0)

            finally:
                self._sock = None

    @staticmethod
    def _read_exactly(sock, count):
        chunks = []
        remaining = count
        while remaining:
            chunk = sock.recv(remaining)
            if not chunk:
                raise ConnectionError("Stream closed")
            chunks.append(chunk)
            remaining -= len(chunk)
        return b"".join(chunks)

    def _consume(self, sock):
        while not self._stop.is_set():
            header = self._read_exactly(sock, 5)
            msg_type, length = struct.unpack(">BI", header)
            payload = self._read_exactly(sock, length)

            if msg_type == TYPE_JSON:
                self._on_json(json.loads(payload.decode("utf-8")))
            elif msg_type == TYPE_FRAME:
                stamp = struct.unpack(">q", payload[:8])[0]
                self._on_frame(stamp, payload[8:])
            else:
                self.get_logger().warn(f"Unknown frame type {msg_type}, skipping...")

    def _on_json(self, sample):
        kind = sample.get("s")
        if kind == "gps":
            self._on_gps(sample)
        elif kind == "accel":
            self._last_accel = sample["v"]
        elif kind == "gyro":
            self._on_imu(sample)
        elif kind == "mag":
            self._on_mag(sample)
        elif kind == "battery":
            self._on_battery(sample)

    def _on_imu(self, sample):
        if self._last_accel is None:
            return

        msg = Imu()
        msg.header.stamp = to_ros_time(sample["t"])
        msg.header.frame_id = self.imu_frame

        gx, gy, gz = android_to_flu(*sample["v"])
        msg.angular_velocity.x = float(gx)
        msg.angular_velocity.y = float(gy)
        msg.angular_velocity.z = float(gz)

        ax, ay, az = android_to_flu(*self._last_accel)
        msg.linear_acceleration.x = float(ax)
        msg.linear_acceleration.y = float(ay)
        msg.linear_acceleration.z = float(az)

        # -1 in the first element is the REP-145 for "no orientation estimate here"
        msg.orientation_covariance[0] = -1.0

        # TODO: Rough fixed covariances. Replace with values from a stationary Allan
        msg.angular_velocity_covariance[0] = 4e-4
        msg.angular_velocity_covariance[4] = 4e-4
        msg.angular_velocity_covariance[8] = 4e-4
        msg.linear_acceleration_covariance[0] = 4e-2
        msg.linear_acceleration_covariance[4] = 4e-2
        msg.linear_acceleration_covariance[8] = 4e-2

        self.pub_imu.publish(msg)

    def _on_mag(self, sample):
        msg = MagneticField()
        msg.header.stamp = to_ros_time(sample["t"])
        msg.header.frame_id = self.imu_frame

        # Android reports microtesla, ROS 2 uses with tesla
        mx, my, mz = android_to_flu(*sample["v"])
        msg.magnetic_field.x = float(mx) * 1e-6
        msg.magnetic_field.y = float(my) * 1e-6
        msg.magnetic_field.z = float(mz) * 1e-6

        self.pub_mag.publish(msg)

    def _on_gps(self, sample):
        provider = sample.get("prov", "gps")

        if not self._logged_provider:
            self._logged_provider = True
            self.get_logger().info(
                f"first fix from '{provider}', "
                f"horizontal accuracy {float(sample.get('acc', 0.0)):.1f} m"
            )

        msg = NavSatFix()
        msg.header.stamp = to_ros_time(sample["t"])
        msg.header.frame_id = self.gps_frame
        msg.status.status = NavSatStatus.STATUS_FIX
        msg.status.service = NavSatStatus.SERVICE_GPS if provider == "gps" else 0
        msg.latitude = float(sample["lat"])
        msg.longitude = float(sample["lon"])
        msg.altitude = float(sample["alt"])

        horiz = float(sample.get("acc", 0.0)) ** 2
        vert = float(sample.get("vacc", 0.0)) ** 2 or horiz
        msg.position_covariance[0] = horiz
        msg.position_covariance[4] = horiz
        msg.position_covariance[8] = vert
        msg.position_covariance_type = NavSatFix.COVARIANCE_TYPE_APPROXIMATED
        self.pub_gps.publish(msg)

    def _on_frame(self, stamp_nanos, jpeg):
        msg = CompressedImage()
        msg.header.stamp = to_ros_time(stamp_nanos)
        msg.header.frame_id = self.camera_frame
        msg.format = "jpeg"
        msg.data = jpeg
        self.pub_img.publish(msg)

    def _on_battery(self, sample):
        msg = BatteryState()
        msg.header.stamp = to_ros_time(sample["t"])
        msg.voltage = float(sample.get("voltage", float("nan")))
        msg.temperature = float(sample.get("temperature", float("nan")))
        msg.current = float(sample.get("current", float("nan")))
        msg.percentage = float(sample.get("percentage", float("nan")))
        msg.present = bool(sample.get("present", True))
        
        status_code = sample.get("status", 1)
        msg.power_supply_status = ANDROID_STATUS_TO_ROS.get(
            status_code, BatteryState.POWER_SUPPLY_STATUS_UNKNOWN
        )

        health_code = sample.get("health", 1)
        msg.power_supply_health = ANDROID_HEALTH_TO_ROS.get(
            health_code, BatteryState.POWER_SUPPLY_HEALTH_UNKNOWN
        )

        msg.power_supply_technology = BatteryState.POWER_SUPPLY_TECHNOLOGY_LION
        self.pub_battery.publish(msg)

    def _on_torch_cmd(self, msg:Bool):
        cmd = json.dumps({"cmd": "torch", "state": bool(msg.data)}) + "\n"
        try:
            self._sock.sendall(cmd.encode("utf-8"))
        except Exception as e:
            self.get_logger().error(f"Failed to send torch command: {e}")

    def _on_vibrate_cmd(self, msg: Int32):
        duration = int(msg.data) if msg.data > 0 else 300
        cmd = json.dumps({
            "cmd": "vibrate", 
            "duration_ms": duration,
            "amplitude": 255
            }) + "\n"
        try:
            self._sock.sendall(cmd.encode("utf-8"))
        except Exception as e:
            self.get_logger().error(f"Failed to send vibrate command: {e}")

def main(args=None):
    rclpy.init(args=args)
    node = MobileSensors()
    try:
        rclpy.spin(node)
    except KeyboardInterrupt:
        pass
    finally:
        node.destroy_node()
        if rclpy.ok():
            rclpy.shutdown()


if __name__ == "__main__":
    main()
