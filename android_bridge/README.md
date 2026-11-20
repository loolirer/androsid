# android_bridge

ROS 2 bridge package for streaming Android sensor telemetry and handling hardware commands.

---

## Overview

`android_bridge` is an `ament_python` package that interfaces ROS 2 with Android devices running the sensor streamer. It ships two generic lifecycle nodes, and the launch file starts one instance per channel: `android_sensor` publishes one topic from one socket, and `android_actuator` serves one service that writes to one socket. Each instance has its own lifecycle, so channels can be started, stopped and fail independently.

---

## Package Architecture & Core Files

The core logic is partitioned across the following modules:

* **`android_bridge/sensors/`**:
  One `Sensor` subclass per supported message type (`Imu`, `MagneticField`, `NavSatFix`, `BatteryState`, `CompressedImage`). Each declares its `msg_type` and overrides `to_msg` to convert one newline-delimited JSON payload: rotating Android sensor axes into the ROS Forward-Left-Up (FLU) body frame, setting REP-145 covariance flags, decoding Base64 JPEG buffers. Subclasses register themselves by message type.

* **`android_bridge/actuators/`**:
  One `Actuator` subclass per supported service type (`SetTorch`). Each declares its `srv_type` and overrides `to_cmd` to turn a request into the JSON command sent to the device.

* **`android_bridge/android_sensor.py`**:
  Generic lifecycle node (`AndroidSensor`). Picks its `Sensor` from the `msg_type` parameter, reads the socket in a background thread and publishes through a lifecycle publisher, so publication follows the node's lifecycle state.

* **`android_bridge/android_actuator.py`**:
  Generic lifecycle node (`AndroidActuator`). Picks its `Actuator` from the `srv_type` parameter and forwards service requests to the socket while active.

* **`launch/android_bridge.launch.py`**:
  Starts one node instance per top-level block in the YAML configs, configures and activates each one, and supports modular bringup via boolean launch arguments (`launch_sensors` and `launch_actuators`).

* **`config/`** *(Configuration and Layout Presets)*:
  Contains node parameter definitions and visualization configurations.

---

## Public API

### Managed Nodes

Instances started by `android_bridge.launch.py`, configured in `config/android_sensors.yaml` and `config/android_actuators.yaml`:

| Node | Type | Socket | Frame ID | Interface |
| :--- | :--- | :--- | :--- | :--- |
| `android_imu` | `sensor_msgs/msg/Imu` | `imu.sock` | `imu_link` | `/imu/data_raw` |
| `android_mag` | `sensor_msgs/msg/MagneticField` | `mag.sock` | `imu_link` | `/imu/mag` |
| `android_gps` | `sensor_msgs/msg/NavSatFix` | `gps.sock` | `gps_link` | `/gps/fix` |
| `android_battery` | `sensor_msgs/msg/BatteryState` | `battery.sock` | *N/A* | `/battery_state` |
| `android_camera_front_0` | `sensor_msgs/msg/CompressedImage` | `camera_front_0.sock` | `camera_front_0_optical_frame` | `/camera/front_0/image_raw/compressed` |
| `android_camera_rear_0` | `sensor_msgs/msg/CompressedImage` | `camera_rear_0.sock` | `camera_rear_0_optical_frame` | `/camera/rear_0/image_raw/compressed` |
| `android_torch` | `android_interfaces/srv/SetTorch` | `torch.sock` | *N/A* | `/set_torch` |

Sockets live under `/run/androsid/`.

### Parameters (`android_sensor`)

| Parameter | Type | Default | Description |
| :--- | :--- | :--- | :--- |
| `msg_type` | `string` | *required* | Message type to publish, as written in the ROS CLI (`sensor_msgs/msg/Imu` or `sensor_msgs/Imu`). |
| `socket_path` | `string` | *required* | UNIX socket the device streams this channel on. Configure fails if it does not exist. |
| `frame_id` | `string` | `''` | Frame ID stamped in message headers. |
| `topic` | `string` | *required* | Topic to publish on. |

### Parameters (`android_actuator`)

| Parameter | Type | Default | Description |
| :--- | :--- | :--- | :--- |
| `srv_type` | `string` | *required* | Service type to serve (`android_interfaces/srv/SetTorch`). |
| `socket_path` | `string` | *required* | UNIX socket the device listens for commands on. Configure fails if it does not exist. |
| `service` | `string` | *required* | Service name to serve. |

### Adding a channel

1. Add a `Sensor` (or `Actuator`) subclass under `android_bridge/sensors/` (or `actuators/`) that overrides `to_msg` (or `to_cmd`), and import it in that package's `__init__.py`.
2. Add the matching Kotlin `Sensor` (or `Actuator`) on the device, with its own socket.
3. Add a parameter block to the YAML config. The launch file starts one node per top-level block.

---

## Build and Execution

### Building

```bash
colcon build --symlink-install --packages-select android_bridge
```

### Running

Bring up both sensor streaming and actuators by default:

```bash
ros2 launch android_bridge android_bridge.launch.py
```

Or launch them individually using conditional arguments:

```bash
ros2 launch android_bridge android_bridge.launch.py launch_actuators:=false launch_sensors:=true
```

### Lifecycle Control

Each instance is managed independently through the ROS 2 lifecycle CLI:

```bash
ros2 lifecycle set /android_camera_rear_0 deactivate

ros2 lifecycle set /android_camera_rear_0 activate
```

A single instance can also be run on its own:

```bash
ros2 run android_bridge android_sensor --ros-args \
  -r __node:=android_imu \
  -p msg_type:=sensor_msgs/msg/Imu \
  -p socket_path:=/run/androsid/imu.sock \
  -p topic:=imu/data_raw \
  -p frame_id:=imu_link
```

### Testing

Run linter compliance and static analysis checks (`ament_lint_common`):

```bash
colcon test --packages-select android_bridge
colcon test-result --verbose
```

---

## License

This package is licensed under the Apache License 2.0. See the [LICENSE](LICENSE) file for details.