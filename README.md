# 🗨️ ZeroGram — Serverless P2P Encrypted Messenger 🇮🇷

<div align="center">

[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.0-CA61CE?style=flat&logo=kotlin)](https://kotlinlang.org)
[![Platform](https://img.shields.io/badge/Platform-Android-34B55A?style=flat&logo=android)](https://developer.android.com)
[![Min SDK](https://img.shields.io/badge/Min%20SDK-31-blue?style=flat&logo=android)](https://developer.android.com)
[![License](https://img.shields.io/badge/License-MIT-blue?style=flat)](LICENSE)
[![Architecture](https://img.shields.io/badge/Architecture-MVVM-FF6D00?style=flat)]()
[![Encryption](https://img.shields.io/badge/Encryption-Signal%20Protocol-20B2AA?style=flat)]()

</div>

---

> 🇮🇷 **Made in Iran, for Everyone.** ZeroGram is a privacy-first, zero-infrastructure messaging app.
> No servers. No accounts. No phone numbers. Just pure peer-to-peer encryption.

---

## 📖 What is ZeroGram?

ZeroGram is a **fully serverless, peer-to-peer encrypted messenger** for Android built with **Kotlin**. 
It enables direct, end-to-end encrypted communication between devices **without any central server** — 
whether on the same **local network** (WiFi Direct/mDNS) or across the **internet** (WebRTC with STUN/TURN + hole punching).

Think of it as **Signal meets AirDrop** — the privacy of Signal's encryption without relying on any infrastructure.

---

## ✨ Features

| Category | Details |
|----------|---------|
| 🔐 **End-to-End Encryption** | Signal Protocol — X3DH key agreement + Double Ratchet for forward secrecy |
| 📡 **Hybrid Connectivity** | **LAN Mode:** WiFi Direct + mDNS (zero internet) / **Internet Mode:** WebRTC + STUN/TURN hole punching |
| 👤 **No Identity Required** | Generate your own Curve25519 identity — no phone, no email, no account |
| 💬 **Messaging** | Text, images, file sharing (coming soon) |
| 📱 **Android Native** | Kotlin 2.0, Jetpack Compose, MVVM architecture |
| 🚫 **No Infrastructure** | Truly peer-to-peer — no relay servers, no backend, no cloud |

---

## 🔐 How It Works

### Encryption (Signal Protocol)

```
Device A                                              Device B
  │                                                     │
  ├─ X3DH Handshake ──────────────────────────────────>│
  │  (Curve25519 key exchange)                          │
  │                                                     │
  ├─ Double Ratchet ──────────────────────────────────>│
  │  • New key per message (forward secrecy)            │
  │  • DH ratchet on role switch (post-compromise)      │
  │  • AES-256-GCM encryption                           │
  │                                                     │
```

### Connection Paths

```
┌──────────────────────────────┐
│  Is peer on same LAN?        │
├─────────────┬────────────────┤
│    YES      │       NO       │
│    │        │       │        │
│    ▼        │       ▼        │
│  LAN Mode   │  Internet Mode  │
│  (WiFi +    │  (WebRTC STUN/  │
│   mDNS)     │   TURN)         │
└─────────────┴────────────────┘
```

---

## 🚀 Quick Start

### Prerequisites
- Android Studio Hedgehog (2023.1.1) or later
- JDK 17
- Android SDK 34
- Android device running **Android 12+ (API 31+)**

### Build

```bash
git clone https://github.com/samanramezani1377-hub/zerogram.git
cd zerogram
./gradlew assembleDebug
```

### Install
```bash
./gradlew installDebug
# APK at: app/build/outputs/apk/debug/app-debug.apk
```

---

## 📁 Project Structure

```
zerogram/
├── app/
│   ├── src/main/java/com/zerogram/
│   │   ├── crypto/          # Signal Protocol integration
│   │   ├── data/            # Room DB, repositories
│   │   ├── domain/          # Use cases, domain models
│   │   ├── transport/       # LAN/WAN transport layer
│   │   ├── ui/              # Jetpack Compose screens
│   │   └── di/              # Hilt dependency injection
│   └── build.gradle.kts
├── gradle/
│   └── libs.versions.toml   # Version catalog
├── build.gradle.kts         # Root build script
├── settings.gradle.kts      # Repository configuration
├── ARCHITECTURE.md          # Full architecture document
├── BUILD.md                 # Build & test guide
└── LICENSE                  # MIT
```

---

## 🏗️ Tech Stack

| Layer | Technology |
|-------|-----------|
| **Language** | Kotlin 2.0.0 |
| **UI** | Jetpack Compose + Material 3 |
| **Architecture** | MVVM + Clean Architecture |
| **DI** | Hilt (Dagger) |
| **Database** | Room (SQLite) |
| **Encryption** | Signal Protocol (libsignal-client) |
| **LAN Discovery** | JmDNS + WiFi Direct |
| **P2P WAN** | WebRTC (Stream WebRTC) |
| **Serialization** | Kotlinx Serialization |
| **Async** | Kotlin Coroutines & Flow |
| **Image Loading** | Coil |

---

## 📚 Documentation

- 📐 **[ARCHITECTURE.md](ARCHITECTURE.md)** — Full system design, data flows, crypto details
- 🔨 **[BUILD.md](BUILD.md)** — Build guide, testing, contributing

---

## 🛡️ Security & Privacy

- ✅ **No phone number, no email, no account** — identity is a cryptographic key pair
- ✅ **End-to-end encrypted** — Signal Protocol with X3DH + Double Ratchet
- ✅ **Forward secrecy** — compromising one key never compromises past messages
- ✅ **No metadata on servers** — there *are* no servers
- ✅ **MIT Licensed** — fully open source, auditable

---

## ⚠️ Known Limitations (v0.1)

| Issue | Status |
|-------|--------|
| Group messaging | Planned (MLS/SenderKey) |
| TURN relay for symmetric NAT | Planned |
| Offline message queue | Planned |
| QR code SDP exchange | Planned |
| File/image sharing UI | Coming soon |

---

## 🌍 ایران — برای هموطنان 🇮🇷

**دقت:** این پروژه در ایران توسعه داده شده. برای بیلد کردن، `settings.gradle.kts` طوری تنظیم شده
که از ترکیب **Aliyun mirrors (چین)** + **Maven Central** + **Google** استفاده کنه. AGP و 
AndroidX از Aliyun میان، KSP و Kotlin compose از Maven Central.

اگه اینترنت معمولی داری، کافیه `git clone` کنی و بیلد بزنی.

---

## 🤝 Contributing

Pull requests are welcome! Read [ARCHITECTURE.md](ARCHITECTURM.md) first — 
the core principle is **no server, ever**. All features must maintain the P2P-first design.

---

## 📄 License

MIT — see [LICENSE](LICENSE).

---

<div align="center">

**ZeroGram** — No Server. No Trace. Just You.

ساخته شده با ❤️ در ایران

</div>
