# androsid

A self-contained app that ships a ROS 2 development environment for Android with out-of-the-box sensor integration.

<p align="center">
  <img src="assets/androsid.png" alt="androsid logo" width="160">
  <br>
  <sub>Logo by <a href="https://www.behance.net/samyacastro">Samya Castro</a></sub>
</p>

## Building the app

Open the `androsid/android` folder in Android Studio and hit `Run` with the phone connected
over USB debugging. Ensure your Android device has `Developer Options` unlocked and
USB debugging enabled there.

This will launch the app on your device. It currently has no UI, so it will present itself with 
a dark screen. The notifications of the app will act as its liveness indicator.

## Setting up the ROS 2 environment

**1. Get a rootfs tarball.** Build `docker/Dockerfile` and export it with `docker/export.sh`. Check
[`docker/README.md`](docker/README.md) for the exact steps, including how to customize the
environment. You'll end up with a `.tar.gz` file.

**2. Install it on the phone.** From any file manager, `Share` that tarball to androsid. Sharing a tarball 
at any time tears down whatever's currently running and reinstalls fresh from the new one.

This will get the ROS 2 environment running! Optionally, you may access it and interact with it 
as you would with any other remote machine by SSHing into it. The following steps show the how-to.

**3. Authorize your SSH key.** SSH is pubkey-only. In order to handle your public key to the app, 
`Share` its file (e.g. `~/.ssh/id_ed25519.pub`) to androsid the same way. It gets appended to 
`/root/.ssh/authorized_keys` inside the rootfs.

**4. SSH to it:**

```bash
ssh -p 8022 root@<phone-ip>
```

## Usage

If everything is working correctly, the bridge node should be publishing on the topics below:

- `/imu/data_raw`
- `/imu/mag`
- `/gps/fix`
- `/camera/<name>/image_raw/compressed`
- `/battery_state`

And serves the following service:

- `/set_torch`

## Customization

To adjust usage to your preference, modify:

- **`android_bridge/config/mobile_sensors.yaml`** sets each topic's QoS profile.
- **`docker/entrypoint.sh`** runs on boot; by default it starts the bridge and `sshd`. Edit it to
  change what auto-starts.
- **`docker/ros2.sh`** (`/etc/profile.d/ros2.sh` in the rootfs) sources the ROS 2 workspace overlay
  for every login shell, and for `entrypoint.sh` itself.

Modifying them on the environment and re-starting the app is enough to apply the changes. Since all 
are baked into the image at build time, you may also re-build and re-share the tarball to have a custom OotB image.

---