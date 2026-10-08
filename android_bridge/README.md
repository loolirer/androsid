# android_bridge

ROS 2 lifecycle bridge node for streaming Android sensor telemetry and handling hardware commands.

---

## Overview

`android_bridge` is an `ament_python` package that interfaces ROS 2 with Android devices running the sensor streamer. It dispatches telemetry via lifecycle-managed publishers.

---

## Package Architecture & Core Files

The core logic is partitioned across the following modules:

* **`android_bridge/android_to_ros.py`**:
  Pure conversion utilities and mathematical transformations. Converts raw newline-delimited JSON payloads into typed `sensor_msgs` (`Imu`, `MagneticField`, `NavSatFix`, `BatteryState`, `CompressedImage`). Applies coordinate frame rotations from native Android sensor axes to the ROS standard Forward-Left-Up (FLU) body frame, sets REP-145 covariance flags, and decodes Base64 JPEG buffers.

* **`android_bridge/mobile_sensors.py`**:
  Managed node implementation (`MobileSensors`) derived from `rclpy.lifecycle.Node`. Manages socket connectivity in a background worker thread, declares configurable ROS2 parameters, creates lifecycle publishers, and exposes service servers. It ensures determinism by controlling when telemetry publication is activated or suppressed based on lifecycle state transitions.

* **`launch/mobile_sensors.launch.py`**:
  Declarative ROS 2 launch description. Configures node startup arguments (such as target host IP, port, frame IDs, and active camera handles) and instantiates the `mobile_sensors` process within the execution graph.

* **`config/`** *(Configuration and Layout Presets)*:
  Contains node parameter definitions (YAML) and dashboard visualization configurations, including Foxglove Studio layouts (`androsid.json`).

---

## Public API

### Managed Nodes

* **`mobile_sensors`**: Lifecycle node handling device streaming and topic dispatching.

### Parameters

| Parameter | Type | Default | Description |
| :--- | :--- | :--- | :--- |
| `host` | `string` | `127.0.0.1` | IP address or hostname of the Android sensor streamer socket. |
| `port` | `integer` | `9870` | TCP port exposed by the Android streamer. |
| `imu_frame` | `string` | `imu_link` | Frame ID stamped in IMU and magnetic field headers. |
| `gps_frame` | `string` | `gps_link` | Frame ID stamped in NavSatFix message headers. |
| `camera_names` | `string_array` | `[front_0,rear_0]` | List of expected camera identifiers. |

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

## Coordinate Conventions

Sensor coordinates from Android hardware are transformed into the ROS Forward-Left-Up (FLU) body frame:
* $X_{\text{ros}} = -Z_{\text{android}}$
* $Y_{\text{ros}} = Y_{\text{android}}$
* $Z_{\text{ros}} = X_{\text{android}}$

Measurements adhere to standard SI units: linear accelerations in $\text{m/s}^2$, angular velocities in $\text{rad/s}$, and magnetic fields in $\text{Tesla}$.

---

## Build and Execution

### Building

```bash
colcon build --symlink-install --packages-select android_bridge
```

### Running

```bash
ros2 launch android_bridge mobile_sensors.launch.py
```

### Lifecycle Control

Manage the node state through the ROS 2 lifecycle CLI:

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