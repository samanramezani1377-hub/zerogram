/**
 * ZeroGram Signaling Server
 *
 * Lightweight WebSocket relay for WebRTC:
 * - Two phones join the same "room" using a PIN
 * - Relay SDP offers/answers between them
 * - Relay ICE candidates in both directions
 *
 * No database, no auth — just a PIN-based matchmaker.
 * Designed for Render.com free tier.
 */

const { WebSocketServer } = require("ws");

const PORT = process.env.PORT || 8080;

const wss = new WebSocketServer({ port: parseInt(PORT) });

console.log(`[ZeroGram] Signaling server listening on port ${parseInt(PORT)}`);

/**
 * rooms: PIN → { socket1, socket2 }
 * When both sockets are present, the room is "matched"
 */
const rooms = new Map();

wss.on("connection", (socket) => {
  console.log("[ZeroGram] New peer connected");

  let currentPin = null;
  let isAlive = true;

  // Ping/pong to detect dead connections
  socket.on("pong", () => { isAlive = true; });

  socket.on("message", (raw) => {
    let msg;
    try {
      msg = JSON.parse(raw.toString());
    } catch {
      return;
    }

    switch (msg.type) {
      // ── JOIN: peer joins a room with a PIN ──────────────────
      case "join": {
        currentPin = msg.pin;
        if (!currentPin) {
          socket.send(JSON.stringify({ type: "error", message: "PIN required" }));
          return;
        }

        let room = rooms.get(currentPin);

        if (!room) {
          // First peer — create the room
          room = { socket1: socket, socket2: null };
          rooms.set(currentPin, room);
          socket.send(JSON.stringify({ type: "waiting" }));
          console.log(`[ZeroGram] Room ${currentPin}: 1st peer joined, waiting...`);
        } else if (!room.socket2) {
          // Second peer — match!
          room.socket2 = socket;

          // Tell both peers to start WebRTC negotiation
          // The one who joined first becomes the "offerer"
          room.socket1.send(JSON.stringify({
            type: "matched",
            role: "offerer",
            peerId: "peer2"
          }));
          socket.send(JSON.stringify({
            type: "matched",
            role: "answerer",
            peerId: "peer1"
          }));
          console.log(`[ZeroGram] Room ${currentPin}: MATCHED! 2 peers connected.`);
        } else {
          // Room is full
          socket.send(JSON.stringify({ type: "error", message: "Room full" }));
        }
        break;
      }

      // ── OFFER: SDP offer from offerer → answerer ─────────────
      case "offer": {
        const room = rooms.get(currentPin);
        if (room?.socket2) {
          room.socket2.send(JSON.stringify({
            type: "offer",
            sdp: msg.sdp,
          }));
        }
        break;
      }

      // ── ANSWER: SDP answer from answerer → offerer ───────────
      case "answer": {
        const room = rooms.get(currentPin);
        if (room?.socket1) {
          room.socket1.send(JSON.stringify({
            type: "answer",
            sdp: msg.sdp,
          }));
        }
        break;
      }

      // ── ICE: relay ICE candidates in both directions ─────────
      case "ice": {
        const room = rooms.get(currentPin);
        if (!room) break;
        const otherSocket = socket === room.socket1 ? room.socket2 : room.socket1;
        if (otherSocket) {
          otherSocket.send(JSON.stringify({
            type: "ice",
            candidate: msg.candidate,
          }));
        }
        break;
      }

      default:
        break;
    }
  });

  // ── DISCONNECT: clean up room ──────────────────────────────
  socket.on("close", () => {
    if (currentPin) {
      const room = rooms.get(currentPin);
      if (room) {
        // Notify the other peer
        const otherSocket = socket === room.socket1 ? room.socket2 : room.socket1;
        if (otherSocket && otherSocket.readyState === 1) {
          otherSocket.send(JSON.stringify({ type: "peer_disconnected" }));
        }
        rooms.delete(currentPin);
        console.log(`[ZeroGram] Room ${currentPin}: closed`);
      }
    }
  });
});

// ── Health Check (for Render) ──────────────────────────────────
wss.on("listening", () => {
  // Start a tiny HTTP server just for health checks
  const http = require("http");
  http.createServer((req, res) => {
    if (req.url === "/healthz") {
      res.writeHead(200, { "Content-Type": "text/plain" });
      res.end("OK");
    }
  }).listen(parseInt(PORT) + 1);
});

// ── Ping all clients every 30s to keep connections alive ───────
const interval = setInterval(() => {
  wss.clients.forEach((socket) => {
    if (!socket.isAlive) {
      socket.terminate();
      return;
    }
    socket.isAlive = false;
    socket.ping();
  });
}, 30000);

wss.on("close", () => clearInterval(interval));
