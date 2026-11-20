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
from sensor_msgs.msg import NavSatFix, NavSatStatus


class GpsSensor(Sensor):

    msg_type = NavSatFix

    def to_msg(self, sample):
        msg = NavSatFix()
        self._header(msg, sample)

        msg.status.status = NavSatStatus.STATUS_FIX
        msg.status.service = (
            NavSatStatus.SERVICE_GPS if sample['provider'] == 'gps' else 0
        )
        msg.latitude = float(sample['latitude'])
        msg.longitude = float(sample['longitude'])
        msg.altitude = float(sample['altitude'])

        horiz = float(sample['accuracy']) ** 2
        vert = float(sample['vertical_accuracy']) ** 2 or horiz
        msg.position_covariance[0] = horiz
        msg.position_covariance[4] = horiz
        msg.position_covariance[8] = vert
        msg.position_covariance_type = NavSatFix.COVARIANCE_TYPE_APPROXIMATED

        return msg
