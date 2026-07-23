# Building ZeroChat

## Prerequisites

- ]*Android Studio** Hedgehog (2023.1.1) or later
- **JDK 17** (comes bundled with Android Studio)
- **Android SDK 34** with build-tools 34.0.0
- **Kotlin 2.0.0** (auto-downloaded by Gradle)
- An Android device running **Android 12+ (API 31+)**

> **Note:** WiFi Direct requires actual hardware. The emulator cannot test LAN features.
> Use two physical Android devices for full testing.

## Quick Start

```bash
# Clone the project
git clone https://github.com/samanramezani1377-hub/ZeroChat.git
cd ZeroChat

# Build debug APK
./gradlew assembleDebug

# Install on connected device
./gradlew installDebug

# Or use Android Studio: File → Open → select zerochat/ folder
```

## Build Variants

| Variant  | Description                          |
|----------|-------------------------------------------------|
| `debug`  | Unoptimized, logging enabled             |
| `release` | Minified (R8), optimized, production     |

```bash
# Build release APK
./gradlew assembleRelease
```

The release APK will be at: `app/build/outputs/apk/release/app-release.apk`

## Testing LAN Mode

1. [**Install on two devices**] connected to the same WiFi network
2. Launch ZeroChat on both
3. Go to **Find Peers** tab (tap + button)
4. Both devices should discover each other via **mDNS** automatically
5. Tap **Connect** on one device to establish a direct TCP connection
6. Once connected, you can send encrypted messages

> **Troubleshooting:** If devices don't see each other:
> - Both must have WiFi enabled (even if no internet)
> - Some routers block mDNS multicast — use manual IP entry instead
> - Try WiFi Direct mode: both devices disconnect from WiFi and use direct P2P

## Testing Internet Mode (WebRTC)

1. Both devices must have internet access (can be on different networks)
2. Device A: tap a contact ℒ app creates a WebRTC offer
3. Share the offer SDP string with Device B (copy-paste, QR code, etc.)
4. Device B: paste the offer ℒ app creates an answer
5. Share the answer back to Device A
6. WebRTC establishes P2P connection via STUN hole punching
7. Messages are encrypted E2E over the DataChannel

> **Note:** WebRTC handshake currently requires a **side channel** for SDP exchange.
> In v0.1, this is done manually. Future versions will use a DHT or QR codes.

## Contributing

1. Read [ARCHITECTURE.md](ARCHITECTURE.md) for system design
2. All new features should maintain serverless P2P principle
3. Encryption must remain Signal Protocol-based
4. Write tests for crypto and networking layers

## License

MIT License — see [LICENSE](LICENSE) file.
