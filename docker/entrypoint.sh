#!/bin/bash
set -e

# SSH Setup
ls /etc/ssh/*_key >/dev/null 2>&1 || ssh-keygen -A

# Run Android / ROS 2 bridge
source /etc/profile.d/ros2.sh
ros2 launch android_bridge mobile_sensors.launch.py &

# Start SSH server
exec /usr/sbin/sshd -D -e