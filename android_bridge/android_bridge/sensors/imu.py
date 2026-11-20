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

from android_bridge.sensors.sensor import Sensor
from sensor_msgs.msg import Imu


class ImuSensor(Sensor):

    msg_type = Imu

    def to_msg(self, sample):
        msg = Imu()
        self._header(msg, sample)

        gx, gy, gz = self.android_to_flu(*sample['gyro'])
        msg.angular_velocity.x = float(gx)
        msg.angular_velocity.y = float(gy)
        msg.angular_velocity.z = float(gz)

        ax, ay, az = self.android_to_flu(*sample['accel'])
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

        return msg
