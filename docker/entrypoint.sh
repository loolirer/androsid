#!/bin/bash
set -e

# SSH Setup
mkdir -p /run/sshd
chmod 755 /run/sshd
ls /etc/ssh/*_key >/dev/null 2>&1 || ssh-keygen -A
passwd -d root

# Uncomment for automatic operation
# source /etc/profile.d/ros2.sh
# ros2 run android_bridge mobile_sensors &

# Start SSH server
exec /usr/sbin/sshd -D -e \
    -o "Port 8022" \
    -o "PasswordAuthentication yes" \
    -o "PermitEmptyPasswords yes" \
    -o "PermitRootLogin yes" \
    -o "UsePAM no"