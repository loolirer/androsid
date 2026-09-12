# androsid

A ROS 2 development environment that runs on an Android smartphone. It is built from a Dockerfile into a proot container under Termux, and ships with a Kotlin app and a ROS 2 bridge node that publishes the phone's sensor data as hardware stamped messages.

<p align="center">
  <img src="assets/androsid.png" alt="androsid logo" width="160">
  <br>
  <sub>Logo by <a href="https://www.behance.net/samyacastro">Samya Castro</a></sub>
</p>

## Building the app

The app is the sensor source: it holds the camera(s), IMU and GPS handles and streams
them over a localhost socket to the node running in the container.

Open the `androsid/android` folder in Android Studio and hit `Run` with the phone connected
over USB debugging. Ensure your Android device has `Developer Options` unlocked and
USB debugging enabled there.

Then launch the app and grant all permissions. There is no UI, so opening the app starts streaming, closing it stops. The notification is your indicator that it's alive.

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

If everything is working correctly, the bridge node should be publishing on the topics below:

- `/imu/data_raw`
- `/imu/mag`
- `/gps/fix`
- `/camera/<name>/image_raw/compressed` (one per available camera, e.g. `/camera/front_0/image_raw/compressed`, `/camera/rear_0/image_raw/compressed`)
- `/battery_state`

You may customize each topic QoS profile by modifying `android_bridge/config/mobile_sensors.yaml`:

```bash
ros2 run android_bridge mobile_sensors --ros-args --params-file \
  $(ros2 pkg prefix android_bridge)/share/android_bridge/config/mobile_sensors.yaml
```

---