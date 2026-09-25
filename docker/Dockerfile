ARG ROS_DISTRO=jazzy
FROM ros:${ROS_DISTRO}-ros-base

ARG DEBIAN_FRONTEND=noninteractive

RUN apt-get update && \
    apt-get install -y --no-install-recommends \
    nano \
    openssh-server \
    && rm -rf /var/lib/apt/lists/*

RUN rm -f /etc/ssh/ssh_host_* && \
    mkdir -p /run/sshd && chmod 755 /run/sshd && \
    sed -i -E 's/^#?Port .*/Port 8022/' /etc/ssh/sshd_config && \
    sed -i -E 's/^#?UsePAM .*/UsePAM no/' /etc/ssh/sshd_config && \
    sed -i '/^AcceptEnv/d' /etc/ssh/sshd_config && \
    touch /root/.hushlogin

WORKDIR /root/ros2_ws

COPY android_bridge src/android_bridge
COPY android_interfaces src/android_interfaces

RUN bash -c "source /opt/ros/${ROS_DISTRO}/setup.bash && \
    colcon build --symlink-install --packages-select android_interfaces android_bridge"

COPY docker/entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

COPY docker/ros2.sh /etc/profile.d/ros2.sh

ENTRYPOINT ["/entrypoint.sh"]
