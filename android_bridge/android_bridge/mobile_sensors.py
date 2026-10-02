import json
import socket
import threading

import rclpy
from rclpy.lifecycle import Node as LifecycleNode
from rclpy.lifecycle import TransitionCallbackReturn
from rclpy.parameter import Parameter
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


class MobileSensors(LifecycleNode):

    def __init__(self):
        super().__init__("mobile_sensors")

        self.declare_parameter("imu_frame", "imu_link")
        self.declare_parameter("gps_frame", "gps_link")
        self.declare_parameter("camera_names", Parameter.Type.STRING_ARRAY)

        self.pubs = {}
        self.srvs = {}

        self._stop = threading.Event()
        self._send_lock = threading.Lock()
        self._thread = None

        self._sock = None
        self._sock_address = "/run/androsid/mobile_sensors.sock"

        self._unrecognized_cameras = set()

    def on_configure(self, state):
        self.imu_frame = self.get_parameter("imu_frame").value
        self.gps_frame = self.get_parameter("gps_frame").value
        self.camera_names = self.get_parameter("camera_names").value

        self.qos_overrides = QoSOverridingOptions(
            policy_kinds=(
                QoSPolicyKind.RELIABILITY,
                QoSPolicyKind.DURABILITY,
                QoSPolicyKind.HISTORY,
                QoSPolicyKind.DEPTH,
            )
        )
        self.pubs["imu"] = self.create_lifecycle_publisher(
            Imu, "imu/data_raw", 10, qos_overriding_options=self.qos_overrides
        )
        self.pubs["mag"] = self.create_lifecycle_publisher(
            MagneticField, "imu/mag", 10, qos_overriding_options=self.qos_overrides
        )
        self.pubs["gps"] = self.create_lifecycle_publisher(
            NavSatFix, "gps/fix", 10, qos_overriding_options=self.qos_overrides
        )
        self.pubs["battery"] = self.create_lifecycle_publisher(
            BatteryState, "battery_state", 10, qos_overriding_options=self.qos_overrides
        )
        for camera_name in self.camera_names:
            self.pubs[f"img_{camera_name}"] = self.create_lifecycle_publisher(
                CompressedImage,
                f"camera/{camera_name}/image_raw/compressed",
                10,
                qos_overriding_options=self.qos_overrides,
            )

        self.srvs["torch"] = self.create_service(
            SetTorch, "set_torch", self._on_set_torch
        )

        self._stop.clear()
        self._thread = threading.Thread(target=self._run, daemon=True)
        self._thread.start()

        self.get_logger().info("Configured")
        return TransitionCallbackReturn.SUCCESS

    def on_activate(self, state):
        result = super().on_activate(state)
        self.get_logger().info("Activated")
        return result

    def on_deactivate(self, state):
        result = super().on_deactivate(state)
        self.get_logger().info("Deactivated")
        return result

    def on_cleanup(self, state):
        self._stop_streaming()
        self._destroy_resources()
        self.get_logger().info("Cleaned Up")
        return TransitionCallbackReturn.SUCCESS

    def on_shutdown(self, state):
        self._stop_streaming()
        self._destroy_resources()
        self.get_logger().info("Shut Down")
        return TransitionCallbackReturn.SUCCESS

    def destroy_node(self):
        try:
            self.trigger_shutdown()
        except Exception as e:
            self.get_logger().warn(f"Graceful shutdown failed: {e}")
        return super().destroy_node()

    def _stop_streaming(self):
        self._stop.set()

        with self._send_lock:
            sock, self._sock = self._sock, None
        if sock is not None:
            try:
                sock.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass

        if self._thread is not None:
            self._thread.join(timeout=1.0)
            if self._thread.is_alive():
                self.get_logger().warn("Reader thread still running after 1s")
            self._thread = None

    def _destroy_resources(self):
        for pub in self.pubs.values():
            self.destroy_publisher(pub)
        self.pubs.clear()

        for srv in self.srvs.values():
            self.destroy_service(srv)
        self.srvs.clear()

    def _run(self):
        while not self._stop.is_set() and rclpy.ok():
            sock = None
            try:
                self.get_logger().info("Connecting to socket...")
                sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
                sock.connect(self._sock_address)
                with self._send_lock:
                    if self._stop.is_set():
                        break
                    self._sock = sock
                self.get_logger().info("Connected!")

                self._consume(sock)

            except OSError as e:
                if self._stop.is_set():
                    break
                self.get_logger().warn(f"Socket error: {e}; retrying in 1s")
                self._stop.wait(1.0)

            finally:
                with self._send_lock:
                    self._sock = None
                if sock is not None:
                    sock.close()

    def _consume(self, sock):
        stream = sock.makefile("r", encoding="utf-8", newline="\n")
        while not self._stop.is_set():
            line = stream.readline()
            if not line:
                raise ConnectionError("Stream closed")
            line = line.strip()
            if not line:
                continue

            try:
                sample = json.loads(line)
                sample_type = sample.get("type")
            except (ValueError, AttributeError) as e:
                self.get_logger().warn(f"Dropping malformed sample: {e}")
                continue

            if sample_type == "imu":
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
        if self.pubs["imu"].is_activated:
            self.pubs["imu"].publish(imu_msg(sample, self.imu_frame))

    def _on_mag(self, sample):
        if self.pubs["mag"].is_activated:
            self.pubs["mag"].publish(mag_msg(sample, self.imu_frame))

    def _on_gps(self, sample):
        if self.pubs["gps"].is_activated:
            self.pubs["gps"].publish(gps_msg(sample, self.gps_frame))

    def _on_frame(self, sample):
        camera_name = sample.get("camera_name", "default")
        pub = self.pubs.get(f"img_{camera_name}")
        if pub is None:
            if camera_name not in self._unrecognized_cameras:
                self._unrecognized_cameras.add(camera_name)
                self.get_logger().warn(
                    f"Received frame from unrecognized camera '{camera_name}'"
                )
            return

        if pub.is_activated:
            pub.publish(frame_msg(sample, f"camera_{camera_name}_optical_frame"))

    def _on_battery(self, sample):
        if self.pubs["battery"].is_activated:
            self.pubs["battery"].publish(battery_msg(sample))

    def _send_command(self, cmd, params):
        if params is None:
            params = {}

        if self._state_machine.current_state[1] != "active":
            self.get_logger().warn(f"Cannot send command '{cmd}': node is not active")
            return False

        payload = {"cmd": cmd}
        payload.update(params)
        cmd_bytes = (json.dumps(payload) + "\n").encode("utf-8")

        try:
            with self._send_lock:
                sock = self._sock
                if sock is None:
                    self.get_logger().warn("Cannot send command: socket is not connected")
                    return False
                sock.sendall(cmd_bytes)
            return True
        except OSError as e:
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
