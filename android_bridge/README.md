# android_bridge

ROS 2 bridge package for streaming Android sensor telemetry and handling hardware commands.

---

## Overview

`android_bridge` is an `ament_python` package that interfaces ROS 2 with Android devices running the sensor streamer. It has two decoupled nodes: one for handling unidirectional sensor telemetry via lifecycle-managed publishers, and another managing event-driven hardware actuation.

---

## Package Architecture & Core Files

The core logic is partitioned across the following modules:

* **`android_bridge/android_to_ros.py`**:
  Pure conversion utilities and mathematical transformations. Converts raw newline-delimited JSON payloads into typed `sensor_msgs` (`Imu`, `MagneticField`, `NavSatFix`, `BatteryState`, `CompressedImage`). Applies coordinate frame rotations from native Android sensor axes to the ROS standard Forward-Left-Up (FLU) body frame, sets REP-145 covariance flags, and decodes Base64 JPEG buffers.

* **`android_bridge/android_sensors.py`**:
  Managed node implementation (`AndroidSensors`) derived from `rclpy.lifecycle.Node`. Manages socket connectivity in a background worker thread, declares configurable ROS2 parameters and creates lifecycle publishers.

* **`android_bridge/android_actuators.py`**:
  Standard node implementation (`AndroidActuators`) derived from `rclpy.node.Node`. Exposes service servers and handles UNIX domain socket communication to dispatch commands to the Android device.

* **`launch/android_bridge.launch.py`**:
  Declarative ROS 2 launch description. Configures node startup arguments, instantiates both nodes and supports modular bringup via boolean launch arguments (`launch_sensors` and `launch_actuators`).

* **`config/`** *(Configuration and Layout Presets)*:
  Contains node parameter definitions and visualization configurations.

---

## Public API

### Managed Nodes

* **`android_sensors`**: Lifecycle node handling device streaming and topic dispatching.
* **`android_actuators`**: Node handling hardware actuation endpoints and command dispatch.

### Parameters (`android_sensors`)

| Parameter | Type | Default | Description |
| :--- | :--- | :--- | :--- |
| `imu_frame` | `string` | `imu_link` | Frame ID stamped in IMU and magnetic field headers. |
| `gps_frame` | `string` | `gps_link` | Frame ID stamped in NavSatFix message headers. |
| `camera_names` | `string_array` | `[front_0,rear_0]` | List of expected camera identifiers. |

### Parameters (`android_actuators`)

| Parameter | Type | Default | Description |
| :--- | :--- | :--- | :--- |
| `socket_address` | `string` | `/run/androsid/mobile_actuators.sock` | Path to the Android command UNIX domain socket. |

### Published Topics

| Topic | Type | Frame ID | Description |
| :--- | :--- | :--- | :--- |
| `imu/data_raw` | `sensor_msgs/msg/Imu` | `imu_link` | Linear acceleration and angular velocity in FLU frame. |
| `imu/mag` | `sensor_msgs/msg/MagneticField` | `imu_link` | Tri-axial magnetic field in Teslas. |
| `gps/fix` | `sensor_msgs/msg/NavSatFix` | `gps_link` | Geodetic coordinates and horizontal/vertical accuracy covariances. |
| `battery_state` | `sensor_msgs/msg/BatteryState` | *N/A* | Telemetry on battery voltage, percentage, health, and charging status. |
| `camera/<name>/image_raw/compressed` | `sensor_msgs/msg/CompressedImage` | `camera_<name>_optical_frame` | JPEG-compressed image stream per declared camera handle. |

### Services

| Service | Type | Description |
| :--- | :--- | :--- |
| `set_torch` | `android_interfaces/srv/SetTorch` | Toggles the device camera torch/flash on or off. |

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

Manage the `android_sensors` node state through the ROS 2 lifecycle CLI:

```bash
ros2 lifecycle set /mobile_sensors configure

ros2 lifecycle set /mobile_sensors deactivate

ros2 lifecycle set /mobile_sensors activate

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