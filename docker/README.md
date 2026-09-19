# How androsid run its ROS 2 environment

The app doesn't understand Dockerfiles or talk to any registry, it only
consumes a plain rootfs tarball and run processes via `proot` to trick them 
into being in a proper rooted system.

In order to get the tarball, you may build and export an image based on 
`docker/Dockerfile`. If you want your own custom environment (extra ROS
packages, a different base, whatever), edit or replace this Dockerfile and
export it the same way.

## Building and exporting your custom image

Targets `linux/arm64`, so on an x86_64 dev machine you
need QEMU registered for cross-arch builds first:

```bash
docker run --privileged --rm tonistiigi/binfmt --install arm64
```

Then build, from the repo root:

```bash
docker buildx build --platform linux/arm64 -f docker/Dockerfile -t androsid:jazzy .
```

Now that you have the built image, you need to convert it to a tarball, flattening 
every layer into one plain filesystem tree.

> VERY IMPORTANT: this step actually drops the image metadata (`ENTRYPOINT`, `WORKDIR`, ...)

As you can see in `Dockerfile`, the `/entrypoint.sh` file itself survives as 
ordinary rootfs content and that's exactly what the app execs via `proot`.

> VERY IMPORTANT: Android won't let an unprivileged app recreate a hardlink on 
> extraction, and this raises warnings on a .tar extraction. For the app to 
> recognize an extraction as valid, it needs to go 100% okay (exit code 0),
> and for that, we need hard dereferencing

`docker/export-rootfs.sh` handles this by extracting to a real
directory and re-archives with `--hard-dereference`, which turns each
hardlink into an independent file copy:

```bash
docker/export.sh androsid:jazzy androsid_jazzy.tar
```

## Customizing your environment at runtime

The base tarball is meant to be minimal and work out of the box, but still
leave room to customize without touching the Dockerfile or rebuilding the
image. Two files serve as the customization surface:

- **`/entrypoint.sh`** runs on boot (app start). By default it only starts
  `sshd`; the ROS2 bridge auto-start is present but commented out.
- **`/etc/profile.d/ros2.sh`** the ROS2 environment workspace overlay.
  `/etc/profile.d/*.sh` is sourced automatically by every login shell, 
  and it also can be used on `entrypoint.sh` for sourcing the workspace 
  for auto-run ROS 2 processes on the app start.

Both files are baked into the image at build time (`COPY`), not written at
runtime, so editing either is just editing a real file in the rootfs.