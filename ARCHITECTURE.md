# ZeroChat — Architecture Document

## Overview

ZeroChat is a fully serverless peer-to-peer encrypted messenger for Android.
It enables direct communication between devices with **no central server**,
using a hybrid transport layer that automatically selects the best available
connection path.

## Design Principles

1. **No Server, Ever** — No central infrastructure, no relay servers required
2. **E2E by Default** — Every message encrypted with Signal Protocol
3. **LAN First** — When peers are on the same network, communicate directly via IP
4. **Internet Fallback** — WebRTC with STUN/TURN for NAT traversal when remote
5. **Self-Sovereign Identity** — Users generate their own identity; no CA needed

---

## System Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    UI Layer (Jetpack Compose)                 │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐    │
│  │ Contacts  │  │Discovery │  │  Chat    │  │ Settings │    │
│  └──────────┘  └──────────┘  └──────────┘  └──────────┘    │
├─────────────────────────────────────────────────────────────┤
│                   Domain Layer (Use Cases)                    │
│  ┌──────────────────┐  ┌──────────────────┐                 │
│  │ SendMessageUseCase│  │  SessionManager  │                 │
│  └──────────────────┘  └──────────────────┘                 │
├─────────────────────────────────────────────────────────────┤
│                     Data / Repository Layer                   │
│  ┌──────────────────┐  ┌──────────────────┐                 │
│  │ MessageRepository│  │  PeerRepository  │                 │
│  └──────────────────┘  └──────────────────┘                 │
│  ┌──────────────────┐                                       │
│  │ ZeroChatDatabase │  (Room / SQLite)                      │
│  └──────────────────┘                                       │
├─────────────────────────────────────────────────────────────┤
│                     Crypto Engine                             │
│  ┌──────────────────────────────────────────────────┐       │
│  │  SignalCryptoEngine                               │       │
│  │  ├── X3DH Key Agreement                           │       │
│  │  ├── Double Ratchet (per-message forward secrecy) │       │
│  │  ├── Curve25519 Identity Keys                     │       │
│  │  └── AES-256-GCM Message Encryption               │       │
│  └──────────────────────────────────────────────────┘       │
├─────────────────────────────────────────────────────────────┤
│                    Transport Router                           │
│  ┌──────────────────────────────────────────────────┐       │
│  │  TransportRouterImpl                              │       │
│  │  ├── LAN preferred (detected automatically)       │       │
│  │  ├── WAN fallback (WebRTC)                        │       │
│  │  └── Per-peer transport state                     │       │
│  └──────────┬───────────────────────┬────────────────┘       │
│             │                       │                        │
│  ┌──────────▼──────┐   ┌───────────▼───────────┐            │
│  │  LanTransport    │   │   WanTransport         │            │
│  │  (WiFi Direct +  │   │   (WebRTC + STUN/TURN) │            │
│  │   mDNS + TCP)    │   │                        │            │
│  └──────────────────┘   └────────────────────────┘            │
└─────────────────────────────────────────────────────────────┘
```

---

## Connection Flow

### LAN Mode (Same Network)

```
Device A                          Device B
   │                                 │
   ├─ mDNS: advertise _zerochat._tcp │
   │                                 ├─ mDNS: advertise _zerochat._tcp
   │                                 │
   ├─ WiFi Direct: discoverPeers()   │
   │                                 ├─ WiFi Direct: discoverPeers()
   │                                 │
   │<──────── mDNS resolve ──────────│
   │                                 │
   ├─ TCP connect to B's IP:45454 ──>│
   │                                 │
   ├────────── TCP connected ───────>│
   │                                 │
   ├── X3DH handshake ──────────────>│  (encrypted)
   │<── X3DH response ───────────────│
   │                                 │
   ├── Double Ratchet messages ─────>│  (E2E encrypted)
   │<── Double Ratchet messages ─────│
```

### WAN Mode (Internet / Different Networks)

```
Device A                          Device B
   │                                 │
   ├── Create WebRTC offer ─────>│   │  (sent via side channel)
   │                                 ├── Set remote offer
   │                                 ├── Create answer
   │<──── WebRTC answer ─────────────│
   │                                 │
   ├── ICE candidates ──────────────>│  (STUN hole punching)
   │<── ICE candidates ──────────────│
   │                                 │
   ├───────── DataChannel open ─────>│
   │                                 │
   ├── X3DH handshake ──────────────>│  (over DataChannel)
   │<── X3DH response ───────────────│
   │                                 │
   ├── Double Ratchet messages ─────>│  (E2E encrypted)
   │<── Double Ratchet messages ─────│
```

---

## Encryption Details

### Identity Generation
- Each device generates a **Curve25519** identity key pair on first launch
- The public key is hashed with SHA-256 → first 16 hex chars = **fingerprint**
- Displayed as `ZC:xxxxxxxx…xxxx`

### Session Establishment (X3DH)
1. Initiator generates ephemeral key pair
2. Initiator fetches responder's pre-key bundle (identity key + signed pre-key)
3. X3DH computes shared secret incorporating:
   - DH(Initiator Identity, Responder Signed Pre-Key)
   - DH(Initiator Ephemeral, Responder Identity)
   - DH(Initiator Ephemeral, Responder Signed Pre-Key)
   - (Optional) DH(Initiator Ephemeral, Responder One-Time Pre-Key)
4. Shared secret → root key + chain key

### Message Encryption (Double Ratchet)
- Each message advances the **sending chain** → new key per message
- Receiving a message advances the **receiving chain**
- New DH ratchet when roles switch → **forward secrecy**
- Message keys are 256-bit AES-GCM

---

## Transport Selection Logic

```
┌──────────────────────────────┐
│  Is peer on same LAN?         │
│  (mDNS found / WiFi Direct)  │
├───────────┬──────────────────┤
│    YES    │       NO          │
│    │      │       │           │
│    ▼      │       ▼           │
│  LAN TCP  │   Is peer reachable│
│  Socket   │   via WebRTC?      │
│           ├─────────┬──────────┤
│           │   YES   │    NO     │
│           │   │     │    │      │
│           │   ▼     │    ▼      │
│           │ WebRTC  │  Queue msg │
│           │ DataCh  │  for retry │
└───────────┴─────────┴───────────┘
```

### Discovery Methods
| Method         | Scope     | Android API                    |
|----------------|-----------|--------------------------------|
| WiFi Direct    | ~50m      | `WifiP2pManager`               |
| mDNS           | Same WiFi | `JmDNS` library                |
| Manual IP      | Any LAN   | Direct TCP connect             |
| STUN (WebRTC)  | Internet  | Google WebRTC library          |

---

## Data Flow: Send Message

```
User types message
    │
    ▼
ChatViewModel.sendMessage()
    │
    ▼
SendMessageUseCase.invoke(peerFingerprint, plaintext)
    │
    ├─ 1. SessionManager.getOrCreateSession() → sessionId
    │
    ├─ 2. CryptoEngine.encrypt(sessionId, plaintext) → ciphertext
    │
    ├─ 3. MessageRepository.saveMessage(message)  // Local DB
    │
    ├─ 4. TransportRouter.send(peerFingerprint, payload)
    │       │
    │       ├─ LAN available?
    │       │   └─ LanTransport.sendData(bytes)
    │       │       └─ TCP Socket write
    │       │
    │       └─ LAN not available?
    │           └─ WanTransport.sendData(bytes)
    │               └─ WebRTC DataChannel send
    │
    └─ 5. MessageRepository.updateStatus(SENT)
```

## Data Flow: Receive Message

```
TCP Socket / DataChannel receives bytes
    │
    ▼
TransportRouter.incomingMessages() flow
    │
    ▼
IncomingMessageHandler
    │
    ├─ 1. Deserialize message (extract sender FP + ciphertext)
    │
    ├─ 2. SessionManager.getSession(senderFingerprint)
    │
    ├─ 3. CryptoEngine.decrypt(sessionId, ciphertext) → plaintext
    │
    ├─ 4. MessageRepository.saveMessage(message)
    │
    └─ 5. UI updates via Flow
```

---

## Database Schema

### messages
| Column           | Type    | Notes                       |
|------------------|---------|-----------------------------|
| id               | TEXT PK | Unique message ID           |
| conversationId   | TEXT    | Peer fingerprint            |
| senderFingerprint| TEXT    | Sender's fingerprint        |
| content          | TEXT    | Encrypted message (Base64)  |
| plainContent     | TEXT    | Decrypted (for local view)  |
| contentType      | TEXT    | TEXT / IMAGE / FILE         |
| timestamp        | INTEGER | Unix epoch ms               |
| status           | TEXT    | SENDING/SENT/DELIVERED/READ |
| isOutgoing       | INTEGER | Boolean                     |

### peers
| Column           | Type    | Notes                       |
|------------------|---------|-----------------------------|
| id               | INTEGER | Auto-increment PK           |
| displayName      | TEXT    | Peer's display name         |
| fingerprint      | TEXT    | Unique peer identifier      |
| publicIdentityKey| TEXT    | Curve25519 public key      |
| lastKnownIp      | TEXT    | Last known IP address       |
| lastKnownPort    | INTEGER | Last known TCP port         |
| lastSeenAt       | INTEGER | Last seen timestamp         |
| discoveryMethod  | TEXT    | MANUAL/MDNS/WIFI_DIRECT     |
| connectionStatus | TEXT    | CONNECTED/CONNECTING/DISCONNECTED |

---

## Security Considerations

- **No phone number required** — identity is a cryptographic key pair
- **Forward secrecy** — compromising one message key does not compromise others
- **Post-compromise security** — Double Ratchet self-heals after key compromise
- **No metadata on servers** — there is no server to log who talks to whom
- **Local storage** — messages are stored locally; encrypted DB can be added

### Known Limitations (v0.1)
- Group messaging not yet implemented (planned: MLS/SenderKey)
- TURN relay server needed for symmetric NAT scenarios
- No offline message queueing (sender & receiver must be online)
- Identity persistence: keys stored in Android Keystore (TBD)

---

## Dependencies

| Library              | Purpose                          |
|----------------------|----------------------------------|
| Signal Protocol      | E2E encryption (X3DH + Ratchet)  |
| Google WebRTC        | WAN transport + NAT traversal    |
| JmDNS                | LAN peer discovery (mDNS)        |
| Android WiFi Direct  | Direct P2P WiFi connections      |
| Jetpack Compose      | UI framework                     |
| Hilt                 | Dependency injection             |
| Room                 | Local SQLite database            |
| Kotlin Coroutines    | Async/concurrency                |
| Kotlinx Serialization| Message serialization            |
