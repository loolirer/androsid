# Copyright 2026 Lorenzo Oliveira
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

import json
import os
import socket
import stat
import threading

from android_bridge.sensors import Sensor
import rclpy
from rclpy.lifecycle import Node as LifecycleNode
from rclpy.lifecycle import TransitionCallbackReturn
from rclpy.qos import QoSPolicyKind
from rclpy.qos_overriding_options import QoSOverridingOptions


class AndroidSensor(LifecycleNode):

    def __init__(self):
        super().__init__('android_sensor')

        self.declare_parameter('msg_type', '')
        self.declare_parameter('socket_path', '')
        self.declare_parameter('frame_id', '')
        self.declare_parameter('topic', '')

        self.sensor = None
        self._pub = None

        self._stop = threading.Event()
        self._thread = None

        self._sock = None
        self._sock_address = None
        self._sock_lock = threading.Lock()

    def on_configure(self, state):
        msg_type = self.get_parameter('msg_type').value
        self._sock_address = self.get_parameter('socket_path').value
        frame_id = self.get_parameter('frame_id').value
        topic = self.get_parameter('topic').value

        required = (
            ('msg_type', msg_type),
            ('socket_path', self._sock_address),
            ('topic', topic),
        )
        for name, value in required:
            if not value:
                self.get_logger().error(f'Parameter "{name}" is required')
                return TransitionCallbackReturn.FAILURE

        try:
            is_socket = stat.S_ISSOCK(os.stat(self._sock_address).st_mode)
        except OSError:
            is_socket = False
        if not is_socket:
            self.get_logger().error(f'Socket "{self._sock_address}" not found')
            return TransitionCallbackReturn.FAILURE

        try:
            self.sensor = Sensor.for_type(msg_type, frame_id)
        except (ValueError, ImportError, AttributeError) as e:
            self.get_logger().error(f'"{msg_type}" is not a ROS message type: {e}')
            return TransitionCallbackReturn.FAILURE
        except KeyError:
            supported = ', '.join(
                f'{t.__module__.split(".")[0]}/msg/{t.__name__}' for t in Sensor.registry
            )
            self.get_logger().error(
                f'No sensor converts to "{msg_type}". Supported: {supported}'
            )
            return TransitionCallbackReturn.FAILURE

        self._pub = self.create_lifecycle_publisher(
            self.sensor.msg_type,
            topic,
            10,
            qos_overriding_options=QoSOverridingOptions(
                policy_kinds=(
                    QoSPolicyKind.RELIABILITY,
                    QoSPolicyKind.DURABILITY,
                    QoSPolicyKind.HISTORY,
                    QoSPolicyKind.DEPTH,
                )
            ),
        )

        self._stop.clear()
        self._thread = threading.Thread(target=self._run, daemon=True)
        self._thread.start()

        return TransitionCallbackReturn.SUCCESS

    def on_cleanup(self, state):
        self._stop_streaming()
        self._destroy_resources()
        return TransitionCallbackReturn.SUCCESS

    def on_shutdown(self, state):
        self._stop_streaming()
        self._destroy_resources()
        return TransitionCallbackReturn.SUCCESS

    def destroy_node(self):
        self._stop_streaming()
        self._destroy_resources()
        return super().destroy_node()

    def _stop_streaming(self):
        with self._sock_lock:
            self._stop.set()
            sock, self._sock = self._sock, None

        if sock is not None:
            try:
                sock.shutdown(socket.SHUT_RDWR)
            except OSError as e:
                self.get_logger().debug(f'Could not shutdown socket: {e}')

        if self._thread is not None:
            self._thread.join()
            self._thread = None

    def _destroy_resources(self):
        if self._pub is not None:
            self.destroy_lifecycle_publisher(self._pub)
            self._pub = None
        self.sensor = None

    def _run(self):
        while not self._stop.is_set() and rclpy.ok():
            sock = None
            try:
                sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
                sock.connect(self._sock_address)
                with self._sock_lock:
                    if self._stop.is_set():
                        break
                    self._sock = sock
                self.get_logger().info('Bridge connected!')
                self._consume(sock)

            except OSError as e:
                if self._stop.is_set():
                    break
                self.get_logger().warn(
                    f'Socket error: {e}; retrying...', throttle_duration_sec=5.0
                )
                self._stop.wait(1.0)

            finally:
                with self._sock_lock:
                    if self._sock is sock:
                        self._sock = None
                if sock is not None:
                    sock.close()

    def _consume(self, sock):
        stream = sock.makefile('r', encoding='utf-8', newline='\n')
        while not self._stop.is_set():
            line = stream.readline()
            if not line:
                raise ConnectionError('Stream closed')
            line = line.strip()
            if not line:
                continue

            try:
                sample = json.loads(line)
            except ValueError as e:
                self.get_logger().warn(
                    f'Dropping malformed sample: {e}',
                    throttle_duration_sec=5.0,
                )
                continue

            try:
                if self._pub.is_activated:
                    self._pub.publish(self.sensor.to_msg(sample))
            except (KeyError, TypeError, ValueError) as e:
                self.get_logger().fatal(
                    f'Message format mismatch for {self.sensor.msg_type.__name__}: {e!r}'
                )
                self._stop.set()
                self.executor.create_task(self.trigger_shutdown)
                return


def main(args=None):
    rclpy.init(args=args)
    node = AndroidSensor()
    try:
        rclpy.spin(node)
    except KeyboardInterrupt:
        pass
    finally:
        node.destroy_node()
        if rclpy.ok():
            rclpy.shutdown()


if __name__ == '__main__':
    main()
