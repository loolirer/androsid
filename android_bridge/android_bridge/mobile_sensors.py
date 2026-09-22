import json
import socket
import threading

import rclpy
from rclpy.node import Node
from rclpy.qos import QoSPolicyKind
from rclpy.qos_overriding_options import QoSOverridingOptions
from sensor_msgs.msg import BatteryState, CompressedImage, Imu, MagneticField, NavSatFix

from android_bridge.android_to_ros import (
    battery_msg,
    frame_msg,
    gps_msg,
    imu_msg,
    mag_msg,
)

from android_interfaces.srv import (
    SetTorch,
)

class MobileSensors(Node):

    def __init__(self):
        super().__init__("mobile_sensors")

        self.declare_parameter("host", "127.0.0.1")
        self.declare_parameter("port", 9870)
        self.declare_parameter("imu_frame", "imu_link")
        self.declare_parameter("gps_frame", "gps_link")

        self.host = self.get_parameter("host").value
        self.port = self.get_parameter("port").value
        self.imu_frame = self.get_parameter("imu_frame").value
        self.gps_frame = self.get_parameter("gps_frame").value

        self.qos_overrides = QoSOverridingOptions(
            policy_kinds=(
                QoSPolicyKind.RELIABILITY,
                QoSPolicyKind.DURABILITY,
                QoSPolicyKind.HISTORY,
                QoSPolicyKind.DEPTH,
            )
        )
        self.pub_imu = self.create_publisher(
            Imu, "imu/data_raw", 10, qos_overriding_options=self.qos_overrides
        )
        self.pub_mag = self.create_publisher(
            MagneticField, "imu/mag", 10, qos_overriding_options=self.qos_overrides
        )
        self.pub_gps = self.create_publisher(
            NavSatFix, "gps/fix", 10, qos_overriding_options=self.qos_overrides
        )
        self.pub_battery = self.create_publisher(
            BatteryState, "battery_state", 10, qos_overriding_options=self.qos_overrides
        )

        self.pub_img = {}

        self.srv_torch = self.create_service(
            SetTorch, "set_torch", self._on_set_torch
        )

        self._last_accel = None

        self._stop = threading.Event()
        self._sock = None
        self._send_lock = threading.Lock()

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

                    self.pub_img.clear()

                    self._consume(sock)

            except OSError as e:
                if self._stop.is_set():
                    break
                self.get_logger().warn(f"Connection failed: {e}; retrying in 2s")
                self._stop.wait(2.0)

            finally:
                self._sock = None

    def _consume(self, sock):
        stream = sock.makefile("r", encoding="utf-8", newline="\n")
        while not self._stop.is_set():
            line = stream.readline()
            if not line:
                raise ConnectionError("Stream closed")
            line = line.strip()
            if not line:
                continue

            sample = json.loads(line)
            sample_type = sample.get("type")
            if sample_type == "accel":
                self._last_accel = sample["axes"]
            elif sample_type == "gyro":
                self._on_imu(sample)
            elif sample_type == "mag":
                self._on_mag(sample)
            elif sample_type == "gps":
                self._on_gps(sample)
            elif sample_type == "frame":
                self._on_frame(sample)
            elif sample_type == "battery":
                self._on_battery(sample)

    def _on_imu(self, sample):
        if self._last_accel is None:
            return

        self.pub_imu.publish(imu_msg(sample, self._last_accel, self.imu_frame))

    def _on_mag(self, sample):
        self.pub_mag.publish(mag_msg(sample, self.imu_frame))

    def _on_gps(self, sample):
        self.pub_gps.publish(gps_msg(sample, self.gps_frame))

    def _on_frame(self, sample):
        camera_name = sample.get("camera_name", "default")
        pub = self.pub_img.get(camera_name)
        if pub is None:
            pub = self.create_publisher(
                CompressedImage,
                f"camera/{camera_name}/image_raw/compressed",
                10,
                qos_overriding_options=self.qos_overrides,
            )
            self.pub_img[camera_name] = pub

        pub.publish(frame_msg(sample, f"camera_{camera_name}_optical_frame"))

    def _on_battery(self, sample):
        self.pub_battery.publish(battery_msg(sample))

    def _send_command(self, cmd, params):
        if params is None:
            params = {}

        if self._sock is None:
            self.get_logger().warn("Cannot send command: TCP socket is not connected")
            return False

        payload = {"cmd": cmd}
        payload.update(params)
        cmd_bytes = (json.dumps(payload) + "\n").encode("utf-8")

        try:
            with self._send_lock:
                sock = self._sock
                if sock is None:
                    raise OSError("Socket disconnected")
                sock.sendall(cmd_bytes)
            return True
        except (OSError, AttributeError) as e:
            self.get_logger().error(f"Failed to send command '{cmd}': {e}")
            return False

    def _on_set_torch(self, request, response):
        response.success = self._send_command("torch", {"enabled": request.data})
        return response


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
