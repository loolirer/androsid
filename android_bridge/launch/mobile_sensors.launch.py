import os

from ament_index_python.packages import get_package_share_directory
from launch import LaunchDescription
from launch.actions import EmitEvent, RegisterEventHandler
from launch.event_handlers import OnProcessStart
from launch.events import matches_action
from launch_ros.actions import LifecycleNode
from launch_ros.event_handlers import OnStateTransition
from launch_ros.events.lifecycle import ChangeState
from lifecycle_msgs.msg import Transition


def generate_launch_description():
    params_file = os.path.join(
        get_package_share_directory("android_bridge"),
        "config",
        "mobile_sensors.yaml",
    )

    mobile_sensors_node = LifecycleNode(
        package="android_bridge",
        executable="mobile_sensors",
        name="mobile_sensors",
        namespace="",
        parameters=[params_file],
    )

    configure_on_start = RegisterEventHandler(
        OnProcessStart(
            target_action=mobile_sensors_node,
            on_start=[
                EmitEvent(
                    event=ChangeState(
                        lifecycle_node_matcher=matches_action(mobile_sensors_node),
                        transition_id=Transition.TRANSITION_CONFIGURE,
                    )
                ),
            ],
        )
    )

    activate_on_configured = RegisterEventHandler(
        OnStateTransition(
            target_lifecycle_node=mobile_sensors_node,
            goal_state="inactive",
            entities=[
                EmitEvent(
                    event=ChangeState(
                        lifecycle_node_matcher=matches_action(mobile_sensors_node),
                        transition_id=Transition.TRANSITION_ACTIVATE,
                    )
                ),
            ],
        )
    )

    return LaunchDescription(
        [
            mobile_sensors_node,
            configure_on_start,
            activate_on_configured,
        ]
    )
