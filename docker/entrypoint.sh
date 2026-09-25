#!/bin/bash
set -e

# SSH Setup
ls /etc/ssh/*_key >/dev/null 2>&1 || ssh-keygen -A

# Uncomment for automatic operation
# source /etc/profile.d/ros2.sh
# ros2 run android_bridge mobile_sensors &

# Start SSH server
exec /usr/sbin/sshd -D -e