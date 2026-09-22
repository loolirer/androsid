#!/bin/bash
set -e

# SSH Setup
mkdir -p /run/sshd
chmod 755 /run/sshd
ls /etc/ssh/*_key >/dev/null 2>&1 || ssh-keygen -A

sed -i -E 's/^#?Port .*/Port 8022/' /etc/ssh/sshd_config
sed -i -E 's/^#?PermitRootLogin .*/PermitRootLogin yes/' /etc/ssh/sshd_config
sed -i -E 's/^#?PasswordAuthentication .*/PasswordAuthentication yes/' /etc/ssh/sshd_config
sed -i -E 's/^#?PermitEmptyPasswords .*/PermitEmptyPasswords yes/' /etc/ssh/sshd_config
sed -i -E 's/^#?UsePAM .*/UsePAM no/' /etc/ssh/sshd_config
sed -i '/^AcceptEnv/d' /etc/ssh/sshd_config

passwd -d root
touch /root/.hushlogin

# Uncomment for automatic operation
# source /etc/profile.d/ros2.sh
# ros2 run android_bridge mobile_sensors &

# Start SSH server
exec /usr/sbin/sshd -D -e