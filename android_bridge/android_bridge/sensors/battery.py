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
from sensor_msgs.msg import BatteryState


class BatterySensor(Sensor):

    msg_type = BatteryState

    STATUSES = {
        'charging': BatteryState.POWER_SUPPLY_STATUS_CHARGING,
        'discharging': BatteryState.POWER_SUPPLY_STATUS_DISCHARGING,
        'not_charging': BatteryState.POWER_SUPPLY_STATUS_NOT_CHARGING,
        'full': BatteryState.POWER_SUPPLY_STATUS_FULL,
    }

    HEALTHS = {
        'good': BatteryState.POWER_SUPPLY_HEALTH_GOOD,
        'overheat': BatteryState.POWER_SUPPLY_HEALTH_OVERHEAT,
        'dead': BatteryState.POWER_SUPPLY_HEALTH_DEAD,
        'overvoltage': BatteryState.POWER_SUPPLY_HEALTH_OVERVOLTAGE,
        'unspecified_failure': BatteryState.POWER_SUPPLY_HEALTH_UNSPEC_FAILURE,
        'cold': BatteryState.POWER_SUPPLY_HEALTH_COLD,
    }

    TECHNOLOGIES = {
        'nimh': BatteryState.POWER_SUPPLY_TECHNOLOGY_NIMH,
        'li-ion': BatteryState.POWER_SUPPLY_TECHNOLOGY_LION,
        'li-poly': BatteryState.POWER_SUPPLY_TECHNOLOGY_LIPO,
        'life': BatteryState.POWER_SUPPLY_TECHNOLOGY_LIFE,
        'nicd': BatteryState.POWER_SUPPLY_TECHNOLOGY_NICD,
        'limn': BatteryState.POWER_SUPPLY_TECHNOLOGY_LIMN,
    }

    def to_msg(self, sample):
        msg = BatteryState()
        self._header(msg, sample)

        msg.voltage = float(sample['voltage'])
        msg.temperature = float(sample['temperature'])
        msg.current = float(sample['current'])
        msg.percentage = float(sample['percentage'])
        msg.present = bool(sample['present'])

        msg.charge = float('nan')
        msg.capacity = float('nan')
        msg.design_capacity = float('nan')

        msg.power_supply_status = self.STATUSES.get(
            sample['status'], BatteryState.POWER_SUPPLY_STATUS_UNKNOWN
        )
        msg.power_supply_health = self.HEALTHS.get(
            sample['health'], BatteryState.POWER_SUPPLY_HEALTH_UNKNOWN
        )
        msg.power_supply_technology = self.TECHNOLOGIES.get(
            sample['technology'], BatteryState.POWER_SUPPLY_TECHNOLOGY_UNKNOWN
        )

        return msg
