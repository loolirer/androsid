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

from android_bridge.actuators import Actuator
import rclpy
from rclpy.lifecycle import Node as LifecycleNode
from rclpy.lifecycle import TransitionCallbackReturn


class AndroidActuator(LifecycleNode):

    def __init__(self):
        super().__init__('android_actuator')

        self.declare_parameter('srv_type', '')
        self.declare_parameter('socket_path', '')
        self.declare_parameter('service', '')

        self.actuator = None
        self._srv = None
        self._service_name = None

        self._stop = threading.Event()
        self._thread = None

        self._sock = None
        self._sock_address = None
        self._send_lock = threading.Lock()

    def on_configure(self, state):
        srv_type = self.get_parameter('srv_type').value
        self._sock_address = self.get_parameter('socket_path').value
        service = self.get_parameter('service').value

        required = (
            ('srv_type', srv_type),
            ('socket_path', self._sock_address),
            ('service', service),
        )
        for name, value in required:
            if not value:
                self.get_logger().error(f'Parameter "{name}" is required')
                return TransitionCallbackReturn.FAILURE

        try:
            self.actuator = Actuator.for_type(srv_type)
        except (ValueError, ImportError, AttributeError) as e:
            self.get_logger().error(f'"{srv_type}" is not a ROS service type: {e}')
            return TransitionCallbackReturn.FAILURE
        except KeyError:
            supported = ', '.join(
                f'{t.__module__.split(".")[0]}/srv/{t.__name__}' for t in Actuator.registry
            )
            self.get_logger().error(
                f'No actuator handles "{srv_type}". Supported: {supported}'
            )
            return TransitionCallbackReturn.FAILURE

        self._service_name = service
        self._srv = self.create_service(
            self.actuator.srv_type, self._service_name, self._on_request
        )

        self._stop.clear()
        self._thread = threading.Thread(target=self._run, daemon=True)
        self._thread.start()

        self.get_logger().info('Configured')
        return TransitionCallbackReturn.SUCCESS

    def on_activate(self, state):
        result = super().on_activate(state)
        self.get_logger().info('Activated')
        return result

    def on_deactivate(self, state):
        result = super().on_deactivate(state)
        self.get_logger().info('Deactivated')
        return result

    def on_cleanup(self, state):
        self._stop_streaming()
        self._destroy_resources()
        self.get_logger().info('Cleaned Up')
        return TransitionCallbackReturn.SUCCESS

    def on_shutdown(self, state):
        self._stop_streaming()
        self._destroy_resources()
        self.get_logger().info('Shut Down')
        return TransitionCallbackReturn.SUCCESS

    def destroy_node(self):
        self._stop_streaming()
        self._destroy_resources()
        return super().destroy_node()

    def _stop_streaming(self):
        with self._send_lock:
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
        if self._srv is not None:
            self.destroy_service(self._srv)
            self._srv = None
        self.actuator = None

    def _run(self):
        while not self._stop.is_set() and rclpy.ok():
            sock = None
            try:
                sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
                sock.connect(self._sock_address)
                with self._send_lock:
                    if self._stop.is_set():
                        break
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

    def _send_command(self, params):
        service = self._service_name

        if self._state_machine.current_state[1] != 'active':
            self.get_logger().warn(f'Cannot call "{service}": node inactive')
            return False

        cmd_bytes = (json.dumps(params) + '\n').encode('utf-8')

        with self._send_lock:
            if self._sock is None:
                self.get_logger().warn(f'Cannot call "{service}": socket not connected')
                return False

            try:
                self._sock.sendall(cmd_bytes)
                return True
            except OSError as e:
                self.get_logger().error(f'Failed to send "{service}" command: {e}')
                try:
                    self._sock.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass
                self._sock = None
                return False

    def _on_request(self, request, response):
        response.success = self._send_command(self.actuator.to_cmd(request))
        return response


def main(args=None):
    rclpy.init(args=args)
    node = AndroidActuator()
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
