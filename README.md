# ZeroChat― Serverless P2P Encrypted Messenger

A fully serverless, peer-to-peer encrypted messenger for Android built with Kotlin.
ZeroChat works both on **local networks** (WiFi Direct/mDNS) and over the **internet** (WebRTC)
without any central server.

## Features

- 🔒 **End-to-End Encryption** — Signal Protocol (X3DH + Double Ratchet)
- 🌌 **Hybrid Connectivity**
  - **LAN Mode:** WiFi Direct + mDNS discovery — direct IP connection, zero internet needed
  - **Internet Mode:** WebRTC with STUN/TURN + hole punching for NAT traversal
- 🔍 **Peer Discovery**
  - Automatic mDNS service discovery on local network
  - Manual IP/peer ID input for internet connections
  - Optional: DHT-based discovery for internet peers
- 💫 **Messaging** — Text, images, and file sharing
- 📱 **Android Native** — Kotlin, Jetpack Compose, modern architecture
- 🚯 **No Server Required** — Truly peer-to-peer, no relay servers, no infrastructure

## Disclaimer

This is a privacy tool. Use responsibly and in accordance with local laws.
The developers assume no liability for misuse.
