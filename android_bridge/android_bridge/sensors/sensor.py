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

from rclpy.time import Time
from rosidl_runtime_py.utilities import get_message


class Sensor:

    registry = {}

    msg_type = None

    def __init_subclass__(cls, **kwargs):
        super().__init_subclass__(**kwargs)
        if cls.msg_type in Sensor.registry:
            raise TypeError(
                f'{cls.__name__} and {Sensor.registry[cls.msg_type].__name__} '
                f'both convert to {cls.msg_type.__name__}'
            )
        Sensor.registry[cls.msg_type] = cls

    @classmethod
    def for_type(cls, msg_type_name, frame_id):
        msg_type = get_message(msg_type_name)
        return cls.registry[msg_type](frame_id)

    def __init__(self, frame_id):
        self.frame_id = frame_id

    def to_msg(self, sample):
        raise NotImplementedError

    # Android device frame to ROS REP-103 FLU (landscape orientation)
    @staticmethod
    def android_to_flu(x, y, z):
        return -z, y, x

    def _header(self, msg, sample):
        msg.header.stamp = Time(nanoseconds=sample['stamp']).to_msg()
        msg.header.frame_id = self.frame_id
