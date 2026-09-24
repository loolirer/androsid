import json
import socket
import threading

import rclpy
from rcl_interfaces.msg import ParameterDescriptor, ParameterType
from rclpy.lifecycle import Node as LifecycleNode
from rclpy.lifecycle import TransitionCallbackReturn
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

        self.declare_parameter("host", "127.0.0.1")
        self.declare_parameter("port", 9870)
        self.declare_parameter("imu_frame", "imu_link")
        self.declare_parameter("gps_frame", "gps_link")
        self.declare_parameter(
            "camera_names",
            [],
            ParameterDescriptor(type=ParameterType.PARAMETER_STRING_ARRAY),
        )

        self.pubs = {}
        self.srvs = {}

        self._stop = threading.Event()
        self._sock = None
        self._send_lock = threading.Lock()
        self._thread = None

    def on_configure(self, state):
        self.host = self.get_parameter("host").value
        self.port = self.get_parameter("port").value
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

        self.get_logger().info("Configured")
        return TransitionCallbackReturn.SUCCESS

    def on_activate(self, state):
        result = super().on_activate(state)

        self._stop.clear()
        self._thread = threading.Thread(target=self._run, daemon=True)
        self._thread.start()
        self.get_logger().info("Streaming started")
        return result

    def on_deactivate(self, state):
        result = super().on_deactivate(state)
        self._stop_streaming()
        self.get_logger().info("Streaming Stopped")
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
        self._stop_streaming()
        return super().destroy_node()

    def _stop_streaming(self):
        self._stop.set()

        sock, self._sock = self._sock, None
        if sock is not None:
            try:
                sock.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass

        if self._thread is not None:
            self._thread.join(timeout=2.0)
            if self._thread.is_alive():
                self.get_logger().warn("Reader thread still running after 2s")
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
            self.get_logger().warn(
                f"Received frame from unrecognized camera '{camera_name}'", once=True
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
