# android_interfaces

Custom ROS 2 message and service definitions for the Androsid project.

---

## Overview

`android_interfaces` is a CMake package (`ament_cmake`) defining the interface used between Android hardware bridges and ROS 2 nodes, decoupling transport interfaces from node implementations.

---

## Public API

### Services (`srv/`)

| Service | Field | Type | Description |
| --- | --- | --- | --- |
| `SetTorch.srv`(Request) | `data` | `bool` | Request: `true` to turn the phone torch on, `false` to turn it off. |
| `SetTorch.srv`(Response) | `success` | `bool` | Response: `true` if the command was successfully applied. |

---

## Build and Test

### Building

Build the package within your colcon workspace:

```bash
colcon build --symlink-install --packages-select android_interfaces

```

### Running Tests

Run static analysis and linter checks (`ament_lint_common`):

```bash
colcon test --packages-select android_interfaces
colcon test-result --verbose

```

---

## Usage in Downstream Packages

### C++ (`CMakeLists.txt`)

```cmake
find_package(android_interfaces REQUIRED)

rosidl_get_typesupport_target(cpp_typesupport_target
  android_interfaces "rosidl_typesupport_cpp")
target_link_libraries(my_target "${cpp_typesupport_target}")

```

### Python (`setup.py` & imports)

Declare `android_interfaces` in `package.xml`, then import in your nodes:

```python
from android_interfaces.srv import SetTorch

```

---

## License

This package is licensed under the Apache License 2.0. See the [LICENSE](../LICENSE) file for details.