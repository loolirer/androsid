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
from rclpy.lifecycle import Node as LifecycleNode
from rclpy.lifecycle import TransitionCallbackReturn


class AndroidActuators(LifecycleNode):

    def __init__(self):
        super().__init__('android_actuators')

        self.srvs = {}

        self._stop = threading.Event()
        self._thread = None

        self._sock = None
        self._sock_address = '/run/androsid/android_actuators.sock'
        self._send_lock = threading.Lock()
        self._is_active = False

    def on_configure(self, state):
        self.srvs['torch'] = self.create_service(
            SetTorch, 'set_torch', self._on_set_torch
        )

        self._stop.clear()
        self._thread = threading.Thread(target=self._run, daemon=True)
        self._thread.start()

        self.get_logger().info('Configured')
        return TransitionCallbackReturn.SUCCESS

    def on_activate(self, state):
        result = super().on_activate(state)
        self._is_active = True
        self.get_logger().info('Activated')
        return result

    def on_deactivate(self, state):
        self._is_active = False
        result = super().on_deactivate(state)
        self.get_logger().info('Deactivated')
        return result

    def on_cleanup(self, state):
        self._is_active = False
        self._stop_streaming()
        self._destroy_resources()
        self.get_logger().info('Cleaned Up')
        return TransitionCallbackReturn.SUCCESS

    def on_shutdown(self, state):
        self._is_active = False
        self._stop_streaming()
        self._destroy_resources()
        self.get_logger().info('Shut Down')
        return TransitionCallbackReturn.SUCCESS

    def destroy_node(self):
        self._is_active = False
        self._stop_streaming()
        self._destroy_resources()
        return super().destroy_node()

    def _stop_streaming(self):
        self._stop.set()
        with self._send_lock:
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
        for srv in self.srvs.values():
            self.destroy_service(srv)
        self.srvs.clear()

    def _run(self):
        while not self._stop.is_set() and rclpy.ok():
            sock = None
            try:
                sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
                sock.connect(self._sock_address)
                if self._stop.is_set():
                    break
                with self._send_lock:
                    self._sock = sock
                self.get_logger().info('Bridge connected!')
                self._wait_disconnect(sock)

            except OSError as e:
                if self._stop.is_set():
                    break
                self.get_logger().warn(
                    f'Socket error: {e}; retrying...', throttle_duration_sec=5.0
                )
                self._stop.wait(1.0)

            finally:
                with self._send_lock:
                    if self._sock is sock:
                        self._sock = None
                if sock is not None:
                    sock.close()

    def _wait_disconnect(self, sock):
        while not self._stop.is_set():
            data = sock.recv(1024)
            if not data:
                raise ConnectionError('Stream closed')

    def _send_command(self, cmd, params=None):
        if not self._is_active:
            self.get_logger().warn(
                f'Cannot send command "{cmd}": node inactive'
            )
            return False

        if params is None:
            params = {}

        payload = {'cmd': cmd}
        payload.update(params)
        cmd_bytes = (json.dumps(payload) + '\n').encode('utf-8')

        with self._send_lock:
            if self._sock is None:
                self.get_logger().warn('Cannot send command: socket not connected')
                return False

            try:
                self._sock.sendall(cmd_bytes)
                return True
            except OSError as e:
                self.get_logger().error(f'Failed to send command "{cmd}": {e}')
                try:
                    self._sock.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass
                self._sock = None
                return False

    def _on_set_torch(self, request, response):
        response.success = self._send_command('torch', {'enabled': request.data})
        return response


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
