# PRoot binaries

androsid runs its ROS 2 environment through [PRoot](https://proot-me.github.io/). The app needs four
native files for that. They are named `lib*.so` so that Android unpacks them into the app's native
library directory, the only place an app is allowed to execute files from.

| File | What it is | License |
|---|---|---|
| `libproot.so` | PRoot ([Termux fork](https://github.com/termux/proot)) | GPL-2.0-or-later |
| `libproot_loader.so` | PRoot's loader | GPL-2.0-or-later |
| `libtalloc.so` | [talloc](https://talloc.samba.org/) (its SONAME is `libtalloc.so.2`) | LGPL-3.0-or-later |
| `libandroid-shmem.so` | [libandroid-shmem](https://github.com/termux/libandroid-shmem) | BSD-3-Clause |

They are not kept in git. [`build.sh`](build.sh) builds them from pinned sources with the Android NDK,
a GitHub workflow runs it and publishes the result as a release, and the Android build downloads them
from there. Each release has the sources of all three projects attached.

## Building the app

Nothing to do by hand. Before every build, Gradle's `fetchProotBinaries` task (in
`android/app/build.gradle.kts`) downloads the files from the release named in
[`android/app/proot-binaries.properties`](../../android/app/proot-binaries.properties) into
`android/app/src/main/jniLibs/arm64-v8a/`, and refuses any file whose SHA-256 differs from the one
pinned there.

To use binaries you built yourself, put them in that folder and pass `-Pproot.local` to Gradle
(or set `proot.local=true` in `~/.gradle/gradle.properties`); the download is skipped.

## Building the binaries

You need Linux (x86_64), the Android NDK r29, `git`, `curl`, `make`, `python3`, `readelf` and GNU
`awk` (`gawk`). Then:

```bash
ANDROID_NDK_HOME=/path/to/android-ndk-r29 native/proot/build.sh
```

The four files, a `SHA256SUMS`, the source of each project and a `BUILD-INFO.txt` end up in
`native/proot/out/`; downloads, sources and build trees go to `native/proot/work/` (the logs are in
`work/logs/`). Both folders are git-ignored. `build.sh verify DIR` sanity-checks any folder that
holds the four files, for example `android/app/src/main/jniLibs/arm64-v8a`.

## Publishing a new set

1. Change the pins at the top of `build.sh` if needed. Everything that gets built is named.
2. Merge the change. Pull requests that touch this folder build the binaries and keep them as a
   workflow artifact, but publish nothing.
3. Tag the commit with the next number (`proot-binaries-1` for the first set) and push the tag:

   ```bash
   git tag proot-binaries-1
   git push origin proot-binaries-1
   ```

   The `PRoot binaries` workflow builds again and creates a release with that tag.
4. Save the `proot-binaries.properties` attached to the release as
   `android/app/proot-binaries.properties` (the release notes show its content too) and commit it.

A tag that already has a release can't be reused; pick the next number.

## What is pinned

| | |
|---|---|
| PRoot | 5.1.107.92, commit `7266fb3`. The version the app used before the build moved here. |
| talloc | 2.5.0, from the tarball on samba.org, checked against a SHA-256. |
| libandroid-shmem | 0.7, commit `7f0bd7e`. |
| Toolchain | Android NDK r29, API level 24, arm64-v8a. The same target as the Termux builds these files used to come from. The app's `minSdk` is 26, so the API level could go up; that would also switch libandroid-shmem to `ASharedMemory`. |

PRoot is built with `PROOT_UNBUNDLE_LOADER`, so the loader is a separate file instead of being
embedded in `libproot.so`; the app tells PRoot where it is through `PROOT_LOADER`. The 32-bit loader
is left out because the app is arm64-only. The talloc build uses the same cross-compilation answers
as the Termux package.

Bit-for-bit reproducible output is not verified.
