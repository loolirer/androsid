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
import socket
import threading

from android_interfaces.srv import (
    SetTorch,
)
import rclpy
from rclpy.node import Node


class AndroidActuators(Node):

    def __init__(self):
        super().__init__('android_actuators')

        self.declare_parameter(
            'socket_address', '/run/androsid/android_actuators.sock'
        )
        self._sock_address = self.get_parameter('socket_address').value

        self._send_lock = threading.Lock()
        self._sock = None

        self.srv_torch = self.create_service(
            SetTorch, 'set_torch', self._on_set_torch
        )
        self.get_logger().info('android_actuators node initialized')

    def _get_or_create_socket(self):
        if self._sock is not None:
            return self._sock

        try:
            sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
            sock.connect(self._sock_address)
            self._sock = sock
            self.get_logger().info('Connected to actuator sock')
            return self._sock
        except OSError as e:
            self.get_logger().warn(
                f'Failed to connect to {self._sock_address}: {e}',
                throttle_duration_sec=2.0,
            )
            return None

    def _send_command(self, cmd, params=None):
        if params is None:
            params = {}

        payload = {'cmd': cmd}
        payload.update(params)
        cmd_bytes = (json.dumps(payload) + '\n').encode('utf-8')

        with self._send_lock:
            sock = self._get_or_create_socket()
            if sock is None:
                return False

            try:
                sock.sendall(cmd_bytes)
                return True
            except OSError as e:
                self.get_logger().error(f'Failed to send command "{cmd}": {e}')
                self._close_socket()
                return False

    def _close_socket(self):
        if self._sock is not None:
            try:
                self._sock.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            try:
                self._sock.close()
            except OSError:
                pass
            self._sock = None

    def _on_set_torch(self, request, response):
        response.success = self._send_command('torch', {'enabled': request.data})
        return response

    def destroy_node(self):
        with self._send_lock:
            self._close_socket()
        return super().destroy_node()


def main(args=None):
    rclpy.init(args=args)
    node = AndroidActuators()
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
