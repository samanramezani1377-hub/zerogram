/**
 * ZeroGram Signaling Server
 *
 * Lightweight WebSocket signaling server for WebRTC connection establishment.
 *
 * Architecture:
 * - Each 8-digit PIN is a "room" with at most 2 peers
 * - Peers exchange SDP offers/answers and ICE candidates through the server
 * - Once WebRTC is established, all data flows P2P (server never sees messages)
 * - Rooms auto-expire after 5 minutes of inactivity
 *
 * Protocol (JSON over WebSocket):
 *   Client → Server:
 *     { type: "join",   pin: "12345678" }
 *     { type: "sdp",    pin: "12345678", sdp: "v=0...", sdpType: "offer"|"answer" }
 *     { type: "ice",    pin: "12345678", candidate: "...", sdpMid: "...", sdpMLineIndex: 0 }
 *     { type: "ping" }
 *
 *   Server → Client:
 *     { type: "joined", pin: "12345678", position: 1|2 }
 *     { type: "peer_joined", pin: "12345678", peerCount: 2 }
 *     { type: "peer_left", pin: "12345678" }
 *     { type: "sdp", sdp: "v=0...", sdpType: "offer"|"answer" }
 *     { type: "ice", candidate: "...", sdpMid: "...", sdpMLineIndex: 0 }
 *     { type: "error", message: "..." }
 *     { type: "pong" }
 */

const { WebSocketServer } = require('ws');
const { randomBytes } = require('crypto');

const PORT = process.env.PORT || 8080;
const ROOM_TIMEOUT_MS = 5 * 60 * 1000; // 5 minutes

/**
 * @typedef {Object} Client
 * @property {import('ws').WebSocket} ws
 * @property {string|null} pin - Current room PIN
 * @property {number} lastPing - Timestamp of last activity
 */

/** @type {Map<string, Client[]>} — PIN → connected clients */
const rooms = new Map();

// ── WebSocket Server ───────────────────────────────────────────────

const wss = new WebSocketServer({ port: PORT });
console.log(`[ZeroGram Signaling] Listening on ws://0.0.0.0:${PORT}`);

wss.on('connection', (ws) => {
  const client = { ws, pin: null, lastPing: Date.now() };
  console.log(`[+] New connection (total: ${wss.clients.size})`);

  ws.on('message', (raw) => {
    try {
      const msg = JSON.parse(raw.toString());
      handleMessage(client, msg);
    } catch (e) {
      send(client, { type: 'error', message: 'Invalid JSON' });
    }
  });

  ws.on('close', () => {
    leaveRoom(client);
    console.log(`[-] Connection closed (remaining: ${wss.clients.size})`);
  });

  ws.on('error', (err) => {
    console.error(`[!] WebSocket error: ${err.message}`);
  });
});

// ── Room Cleanup ───────────────────────────────────────────────────

setInterval(() => {
  const now = Date.now();
  for (const [pin, clients] of rooms.entries()) {
    // Remove stale clients
    const active = clients.filter(c => (now - c.lastPing) < ROOM_TIMEOUT_MS);
    if (active.length === 0) {
      rooms.delete(pin);
      console.log(`[Room] Deleted empty room ${pin}`);
    } else if (active.length !== clients.length) {
      rooms.set(pin, active);
      console.log(`[Room] Cleaned ${pin}: ${active.length} client(s)`);
    }
  }
}, 60000);

// ── Message Handler ────────────────────────────────────────────────

/**
 * @param {Client} client
 * @param {Object} msg
 */
function handleMessage(client, msg) {
  client.lastPing = Date.now();

  switch (msg.type) {
    case 'join':
      handleJoin(client, msg.pin);
      break;

    case 'sdp':
      relayToOthers(client, msg);
      break;

    case 'ice':
      relayToOthers(client, msg);
      break;

    case 'ping':
      send(client, { type: 'pong' });
      break;

    default:
      send(client, { type: 'error', message: `Unknown type: ${msg.type}` });
  }
}

// ── Join / Leave ───────────────────────────────────────────

/**
 * @param {Client} client
 * @param {string} pin
 */
function handleJoin(client, pin) {
  // Validate PIN: exactly 8 digits
  if (!pin || !/^\d{8}$/.test(pin)) {
    send(client, { type: 'error', message: 'PIN must be exactly 8 digits' });
    return;
  }

  // Leave previous room if any
  leaveRoom(client);

  // Get or create room
  let room = rooms.get(pin);
  if (!room) {
    room = [];
    rooms.set(pin, room);
    console.log(`[Room] Created ${pin}`);
  }

  // Check room capacity
  if (room.length >= 2) {
    send(client, { type: 'error', message: 'Room is full (max 2 peers)' });
    return;
  }

  // Join
  client.pin = pin;
  room.push(client);

  send(client, {
    type: 'joined',
    pin,
    position: room.length,
  });

  console.log(`[Room] ${pin}: client joined (${room.length}/2)`);

  // Notify first peer that second peer joined
  if (room.length === 2) {
    // Send peer_joined to the FIRST peer (index 0)
    send(room[0], { type: 'peer_joined', pin, peerCount: 2 });
  }
}

/**
 * @param {Client} client
 */
function leaveRoom(client) {
  if (!client.pin) return;

  const room = rooms.get(client.pin);
  if (!room) return;

  const idx = room.indexOf(client);
  if (idx === -1) return;

  room.splice(idx, 1);
  console.log(`[Room] ${client.pin}: client left (${room.length}/2)`);

  if (room.length === 0) {
    rooms.delete(client.pin);
  } else {
    // Notify remaining peer
    send(room[0], { type: 'peer_left', pin: client.pin });
  }

  client.pin = null;
}

// ── Relaying ───────────────────────────────────────────────

/**
 * Relay a message to all OTHER clients in the same room.
 * @param {Client} sender
 * @param {Object} msg
 */
function relayToOthers(sender, msg) {
  if (!sender.pin) {
    send(sender, { type: 'error', message: 'Not in a room. Send "join" first.' });
    return;
  }

  const room = rooms.get(sender.pin);
  if (!room) return;

  for (const peer of room) {
    if (peer !== sender && peer.ws.readyState === 1) {
      // Forward the message to the peer
      peer.ws.send(JSON.stringify(msg));
    }
  }
}

// ── Utilities ──────────────────────────────────────────────

/**
 * @param {Client} client
 * @param {Object} msg
 */
function send(client, msg) {
  if (client.ws.readyState === 1) {
    client.ws.send(JSON.stringify(msg));
  }
}

// Health check endpoint (for fly.io / Railway)
const http = require('http');
http.createServer((req, res) => {
  if (req.url === '/health') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ status: 'ok', clients: wss.clients.size }));
  } else {
    res.writeHead(404);
    res.end();
  }
}).listen(PORT + 1, () => {
  console.log(`[Health] HTTP on port ${PORT + 1}`);
});

// Graceful shutdown
process.on('SIGTERM', () => {
  console.log('[Shutdown] Closing server...');
  wss.close(() => process.exit(0));
});
