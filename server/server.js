// ============================================================
// GRAFONIA SCREEN MIRROR - SERVER
// Real-time mobile screen mirroring via WebSocket
// ============================================================

require("dotenv").config();
const express = require("express");
const http = require("http");
const WebSocket = require("ws");
const cors = require("cors");
const path = require("path");
const QRCode = require("qrcode");
const os = require("os");

const app = express();
const PORT = process.env.PORT || 3000;
const HOST = process.env.HOST || "0.0.0.0";

// ──────────────────────────────────────────────
// Middleware
// ──────────────────────────────────────────────
app.use(cors());
app.use(express.json());

// Serve frontend static files from /frontend folder
app.use(express.static(path.join(__dirname, "../frontend")));

// ──────────────────────────────────────────────
// HTTP Server
// ──────────────────────────────────────────────
const server = http.createServer(app);

// ──────────────────────────────────────────────
// WebSocket Server
// ──────────────────────────────────────────────
const wss = new WebSocket.Server({ server });

// Track connected clients
let senderSocket = null;       // The Android device streaming screen
const viewers = new Set();     // Browser clients watching the stream

// Stream statistics
let stats = {
  framesReceived: 0,
  framesSent: 0,
  connectedAt: null,
  deviceName: "Unknown Device",
  deviceInfo: {},
  fps: 0,
  lastFpsTime: Date.now(),
  fpsFrameCount: 0,
};

// Calculate FPS every second
setInterval(() => {
  const now = Date.now();
  const elapsed = (now - stats.lastFpsTime) / 1000;
  stats.fps = Math.round(stats.fpsFrameCount / elapsed);
  stats.fpsFrameCount = 0;
  stats.lastFpsTime = now;

  // Broadcast stats to viewers
  if (viewers.size > 0) {
    broadcastToViewers(
      JSON.stringify({
        type: "stats",
        fps: stats.fps,
        viewers: viewers.size,
        deviceName: stats.deviceName,
      }),
      true
    );
  }
}, 1000);

// ──────────────────────────────────────────────
// WebSocket Connection Handler
// ──────────────────────────────────────────────
wss.on("connection", (ws, req) => {
  const clientIp = req.socket.remoteAddress;
  console.log(`[+] New WebSocket connection from ${clientIp}`);

  ws.isAlive = true;

  // Ping-pong heartbeat to detect dead connections
  ws.on("pong", () => {
    ws.isAlive = true;
  });

  ws.on("message", (data, isBinary) => {
    try {
      // ── Binary data = screen frame from Android ──
      if (isBinary) {
        if (senderSocket !== ws) {
          console.log(`[!] Binary frame received from non-sender. Ignored.`);
          return;
        }

        stats.framesReceived++;
        stats.fpsFrameCount++;

        // Broadcast frame to all viewers
        broadcastToViewers(data, true);
        return;
      }

      // ── Text data = JSON control messages ──
      const message = JSON.parse(data.toString());

      switch (message.type) {
        // Android sender registers itself
        case "register_sender":
          handleSenderRegistration(ws, message, clientIp);
          break;

        // Browser viewer registers itself
        case "register_viewer":
          handleViewerRegistration(ws, clientIp);
          break;

        // Ping from either side
        case "ping":
          ws.send(JSON.stringify({ type: "pong", time: Date.now() }));
          break;

        default:
          console.log(`[?] Unknown message type: ${message.type}`);
      }
    } catch (err) {
      console.error(`[ERROR] Message processing failed:`, err.message);
    }
  });

  ws.on("close", () => {
    handleDisconnect(ws, clientIp);
  });

  ws.on("error", (err) => {
    console.error(`[ERROR] WebSocket error from ${clientIp}:`, err.message);
  });
});

// ──────────────────────────────────────────────
// Handler: Android Sender Registration
// ──────────────────────────────────────────────
function handleSenderRegistration(ws, message, ip) {
  if (senderSocket && senderSocket.readyState === WebSocket.OPEN) {
    console.log(`[!] Another sender tried to connect. Replacing old sender.`);
    senderSocket.close();
  }

  senderSocket = ws;
  stats.connectedAt = Date.now();
  stats.deviceName = message.deviceName || "Android Device";
  stats.deviceInfo = message.deviceInfo || {};
  stats.framesReceived = 0;
  stats.framesSent = 0;

  console.log(`[SENDER] Android connected: ${stats.deviceName} from ${ip}`);

  // Confirm registration to sender
  ws.send(
    JSON.stringify({
      type: "registered",
      role: "sender",
      message: "You are now streaming",
      viewers: viewers.size,
    })
  );

  // Notify all viewers that sender is online
  broadcastToViewers(
    JSON.stringify({
      type: "sender_connected",
      deviceName: stats.deviceName,
      deviceInfo: stats.deviceInfo,
    }),
    true
  );
}

// ──────────────────────────────────────────────
// Handler: Browser Viewer Registration
// ──────────────────────────────────────────────
function handleViewerRegistration(ws, ip) {
  viewers.add(ws);
  console.log(`[VIEWER] Browser connected from ${ip}. Total viewers: ${viewers.size}`);

  // Send current status to new viewer
  const status = senderSocket && senderSocket.readyState === WebSocket.OPEN
    ? {
        type: "sender_connected",
        deviceName: stats.deviceName,
        deviceInfo: stats.deviceInfo,
      }
    : {
        type: "sender_disconnected",
      };

  ws.send(JSON.stringify(status));

  // Notify sender about new viewer count
  if (senderSocket && senderSocket.readyState === WebSocket.OPEN) {
    senderSocket.send(
      JSON.stringify({ type: "viewer_count", viewers: viewers.size })
    );
  }
}

// ──────────────────────────────────────────────
// Handler: Disconnect
// ──────────────────────────────────────────────
function handleDisconnect(ws, ip) {
  if (ws === senderSocket) {
    senderSocket = null;
    console.log(`[SENDER] Android disconnected from ${ip}`);

    broadcastToViewers(
      JSON.stringify({ type: "sender_disconnected" }),
      true
    );
  } else if (viewers.has(ws)) {
    viewers.delete(ws);
    console.log(
      `[VIEWER] Browser disconnected from ${ip}. Remaining: ${viewers.size}`
    );

    if (senderSocket && senderSocket.readyState === WebSocket.OPEN) {
      senderSocket.send(
        JSON.stringify({ type: "viewer_count", viewers: viewers.size })
      );
    }
  }
}

// ──────────────────────────────────────────────
// Broadcast to all viewers
// ──────────────────────────────────────────────
function broadcastToViewers(data, isBinary = false) {
  const deadSockets = [];

  viewers.forEach((viewer) => {
    if (viewer.readyState === WebSocket.OPEN) {
      try {
        viewer.send(data, { binary: isBinary });
        if (!isBinary) return; // Only count frame sends
        stats.framesSent++;
      } catch (err) {
        console.error(`[ERROR] Failed to send to viewer:`, err.message);
        deadSockets.push(viewer);
      }
    } else {
      deadSockets.push(viewer);
    }
  });

  // Clean up dead sockets
  deadSockets.forEach((s) => viewers.delete(s));
}

// ──────────────────────────────────────────────
// Heartbeat: Remove dead connections every 30s
// ──────────────────────────────────────────────
const heartbeatInterval = setInterval(() => {
  wss.clients.forEach((ws) => {
    if (!ws.isAlive) {
      ws.terminate();
      return;
    }
    ws.isAlive = false;
    ws.ping();
  });
}, 30000);

wss.on("close", () => clearInterval(heartbeatInterval));

// ──────────────────────────────────────────────
// REST API Routes
// ──────────────────────────────────────────────

// Status endpoint
app.get("/api/status", (req, res) => {
  res.json({
    status: "online",
    senderConnected: senderSocket !== null && senderSocket.readyState === WebSocket.OPEN,
    viewers: viewers.size,
    deviceName: stats.deviceName,
    fps: stats.fps,
    framesReceived: stats.framesReceived,
    framesSent: stats.framesSent,
    uptime: process.uptime(),
  });
});

// QR Code for easy connection from Android
app.get("/api/qr", async (req, res) => {
  try {
    const host = req.headers.host || `localhost:${PORT}`;
    const protocol = req.headers["x-forwarded-proto"] || "ws";
    const wsProtocol = protocol === "https" ? "wss" : "ws";
    const wsUrl = `${wsProtocol}://${host}`;

    const qr = await QRCode.toDataURL(wsUrl, {
      width: 300,
      margin: 2,
      color: { dark: "#00ffff", light: "#0a0a0f" },
    });

    res.json({ qr, url: wsUrl });
  } catch (err) {
    res.status(500).json({ error: "QR generation failed" });
  }
});

// Serve frontend for all other routes (SPA support)
app.get("*", (req, res) => {
  res.sendFile(path.join(__dirname, "../frontend/index.html"));
});

// ──────────────────────────────────────────────
// Start Server
// ──────────────────────────────────────────────
server.listen(PORT, HOST, () => {
  const localIp = getLocalIp();
  console.log(`\n╔══════════════════════════════════════════╗`);
  console.log(`║       GRAFONIA SCREEN MIRROR SERVER      ║`);
  console.log(`╠══════════════════════════════════════════╣`);
  console.log(`║  Local:   http://localhost:${PORT}          ║`);
  console.log(`║  Network: http://${localIp}:${PORT}       ║`);
  console.log(`║  WS URL:  ws://${localIp}:${PORT}         ║`);
  console.log(`╚══════════════════════════════════════════╝\n`);
});

// ──────────────────────────────────────────────
// Helper: Get Local IP
// ──────────────────────────────────────────────
function getLocalIp() {
  const interfaces = os.networkInterfaces();
  for (const name of Object.keys(interfaces)) {
    for (const iface of interfaces[name]) {
      if (iface.family === "IPv4" && !iface.internal) {
        return iface.address;
      }
    }
  }
  return "localhost";
}

module.exports = { app, server };
