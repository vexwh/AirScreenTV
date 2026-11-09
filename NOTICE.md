# Third-Party Notices

AirScreen TV is licensed under GPL-3.0. It includes or links against the
following components. This file is informational; the licenses of the upstream
projects govern their respective code and binaries.

## Native AirPlay engine

- **jqssun/android-airplay-server** — GPL-3.0
  https://github.com/jqssun/android-airplay-server
- **UxPlay** — GPL-3.0
  https://github.com/FDH2/UxPlay
- **FFmpeg** — LGPL-2.1+ / GPL-2.0+ depending on build configuration
  https://ffmpeg.org/legal.html
- **libplist** — LGPL-2.1
  https://github.com/libimobiledevice/libplist
- **OpenSSL** — Apache-2.0
  https://www.openssl.org/source/license.html
- **Oboe** — Apache-2.0
  https://github.com/google/oboe

The prebuilt native libraries in airplay2-engine/src/main/jniLibs are built from
the Android AirPlay server project above. Corresponding source is available at
the project links. If you redistribute the APK, keep these notices and the GPL
source links available.

## Android and Kotlin libraries

- AndroidX / Jetpack Compose / Media3 — Apache-2.0
  https://developer.android.com/jetpack/androidx
- Kotlin and kotlinx.coroutines — Apache-2.0
  https://kotlinlang.org/
- Dagger/Hilt — Apache-2.0
  https://dagger.dev/
- Netty — Apache-2.0
  https://netty.io/
- jmDNS — Apache-2.0
  https://github.com/jmdns/jmdns
- Bouncy Castle — MIT
  https://www.bouncycastle.org/licence.html
- ZXing — Apache-2.0
  https://github.com/zxing/zxing

## Icons / assets

No third-party icon packs are intentionally bundled. Android system and
Material icons are used under their respective licenses.
