# androsid

A ROS 2 development environment that runs on an Android smartphone. It is built from a
Dockerfile into a proot container under Termux, and ships with a Kotlin sensor bridge 
that publishes the phone's camera, IMU and GPS as `sensor_msgs`, hardware stamped and on
one clock.

The bridge is split into an app and a ROS 2 node because a proot container cannot
reach Android's camera, sensor or location HALs. Going through an app also allows
hardware timestamps, taken at the sensor rather than at the moment a pipe happened to
be scheduled.

## Building the app

The app is the sensor source: it holds the camera, IMU and GPS handles and streams
them over a localhost socket to the node running in the container.

Open the `androsid/android` folder in Android Studio and hit `Run` with the phone connected
over USB debugging. Ensure your Android device has `Developer Options` unlocked and
USB debugging enabled there.

Then launch the app and grant camera + location + notifications. There is no UI:
opening the app starts streaming, closing it stops. The notification is your
indicator that it's alive.

## Setting up a ROS 2 environment on your device

Install [Termux](https://f-droid.org/packages/com.termux/) app on your android device, and inside it:

```bash
pkg install proot-distro openssh git nano
echo "pgrep -x sshd >/dev/null || sshd" >> ~/.bashrc
passwd
whoami
```

`passwd` sets your ssh password and `whoami` prints your ssh username. The server
listens on port 8022, so from your computer:

```bash
ssh -p 8022 u0_aXXX@<phone-ip>
```

Clone the repo:

```bash
git clone https://github.com/loolirer/androsid.git && cd androsid
```

Build the image and access the container (tested on Jazzy, but you may change it via `--build-arg ROS_DISTRO=<distro>`):

```bash
proot-distro build -f docker/Dockerfile -t androsid:jazzy --install-as androsid .
proot-distro login androsid
```

Alternatively, to ssh directly into the container from your computer on the nexts runs:

```bash
ssh -p 8022 u0_aXXX@<phone-ip> -t 'proot-distro login androsid'
```

And run the bridge:

```bash
ros2 run android_bridge mobile_sensors
```

## Usage

If everything is working correctly, the bridge node should be publishing on the
topics below:

- `/imu/data_raw`
- `/imu/mag`
- `/gps/fix`
- `/camera/image_raw/compressed`
- `/battery/state`

You can also control device actuators:

- /flashlight/cmd (`std_msgs/msg/Bool`): Turn the camera torch/flash on or off

Turn on:
```bash
ros2 topic pub --once /flashlight/cmd std_msgs/msg/Bool "{data: true}"
```

Turn off:
```bash
ros2 topic pub --once /flashlight/cmd std_msgs/msg/Bool "{data: false}"
```
---