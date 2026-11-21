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

from rosidl_runtime_py.utilities import get_service


class Actuator:

    registry = {}

    srv_type = None

    def __init_subclass__(cls, **kwargs):
        super().__init_subclass__(**kwargs)
        if cls.srv_type in Actuator.registry:
            raise TypeError(
                f'{cls.__name__} and {Actuator.registry[cls.srv_type].__name__} '
                f'both handle {cls.srv_type.__name__}'
            )
        Actuator.registry[cls.srv_type] = cls

    @classmethod
    def for_type(cls, srv_type_name):
        srv_type = get_service(srv_type_name)
        return cls.registry[srv_type]()

    def to_cmd(self, request):
        raise NotImplementedError
