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

import os

from ament_index_python.packages import get_package_share_directory
from launch import LaunchDescription
from launch.actions import DeclareLaunchArgument
from launch.conditions import IfCondition
from launch.substitutions import LaunchConfiguration
from launch_ros.actions import LifecycleNode
import yaml


def _instances(executable, params_file, condition_arg):
    with open(params_file) as f:
        names = [key.lstrip('/') for key in yaml.safe_load(f)]

    return [
        LifecycleNode(
            package='android_bridge',
            executable=executable,
            exec_name=name,
            name=name,
            namespace='',
            parameters=[params_file],
            autostart=True,
            condition=IfCondition(LaunchConfiguration(condition_arg)),
        )
        for name in names
    ]


def generate_launch_description():
    config_dir = os.path.join(get_package_share_directory('android_bridge'), 'config')

    launch_sensors = DeclareLaunchArgument(
        'launch_sensors',
        default_value='true',
        description='Launch the sensor nodes'
    )
    launch_actuators = DeclareLaunchArgument(
        'launch_actuators',
        default_value='true',
        description='Launch the actuator nodes'
    )

    return LaunchDescription(
        [
            launch_actuators,
            launch_sensors,
            *_instances(
                'android_sensor',
                os.path.join(config_dir, 'android_sensors.yaml'),
                'launch_sensors',
            ),
            *_instances(
                'android_actuator',
                os.path.join(config_dir, 'android_actuators.yaml'),
                'launch_actuators',
            ),
        ]
    )
