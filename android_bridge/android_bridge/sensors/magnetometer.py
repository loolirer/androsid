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
from sensor_msgs.msg import MagneticField


class MagSensor(Sensor):

    msg_type = MagneticField

    def to_msg(self, sample):
        msg = MagneticField()
        self._header(msg, sample)

        mx, my, mz = self.android_to_flu(*sample['mag'])
        msg.magnetic_field.x = float(mx)
        msg.magnetic_field.y = float(my)
        msg.magnetic_field.z = float(mz)

        return msg
