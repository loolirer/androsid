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
from launch.actions import DeclareLaunchArgument, EmitEvent, GroupAction, RegisterEventHandler
from launch.conditions import IfCondition
from launch.event_handlers import OnProcessStart
from launch.events import matches_action
from launch.substitutions import LaunchConfiguration
from launch_ros.actions import LifecycleNode
from launch_ros.event_handlers import OnStateTransition
from launch_ros.events.lifecycle import ChangeState
from lifecycle_msgs.msg import Transition


def _lifecycle_group(node, condition_arg):
    configure_on_start = RegisterEventHandler(
        OnProcessStart(
            target_action=node,
            on_start=[
                EmitEvent(
                    event=ChangeState(
                        lifecycle_node_matcher=matches_action(node),
                        transition_id=Transition.TRANSITION_CONFIGURE,
                    )
                ),
            ],
        )
    )

    activate_on_configured = RegisterEventHandler(
        OnStateTransition(
            target_lifecycle_node=node,
            goal_state='inactive',
            entities=[
                EmitEvent(
                    event=ChangeState(
                        lifecycle_node_matcher=matches_action(node),
                        transition_id=Transition.TRANSITION_ACTIVATE,
                    )
                ),
            ],
        )
    )

    return GroupAction(
        actions=[
            node,
            configure_on_start,
            activate_on_configured,
        ],
        condition=IfCondition(LaunchConfiguration(condition_arg)),
    )


def generate_launch_description():
    params_file = os.path.join(
        get_package_share_directory('android_bridge'),
        'config',
        'android_sensors.yaml',
    )

    launch_sensors = DeclareLaunchArgument(
        'launch_sensors',
        default_value='true',
        description='Launch the android_sensors node'
    )
    launch_actuators = DeclareLaunchArgument(
        'launch_actuators',
        default_value='true',
        description='Launch the android_actuators node'
    )

    android_sensors_node = LifecycleNode(
        package='android_bridge',
        executable='android_sensors',
        name='android_sensors',
        namespace='',
        parameters=[params_file],
    )

    android_actuators_node = LifecycleNode(
        package='android_bridge',
        executable='android_actuators',
        name='android_actuators',
        namespace='',
    )

    return LaunchDescription(
        [
            launch_actuators,
            launch_sensors,
            _lifecycle_group(android_sensors_node, 'launch_sensors'),
            _lifecycle_group(android_actuators_node, 'launch_actuators')
        ]
    )
