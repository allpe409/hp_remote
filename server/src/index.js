import express from "express";
import { createServer } from "node:http";
import { WebSocketServer } from "ws";
import path from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const PORT = process.env.PORT || 8080;

const app = express();
app.use(express.static(path.join(__dirname, "..", "public")));

const server = createServer(app);
const wss = new WebSocketServer({ server, path: "/ws" });

/** @type {Map<string, { device: import("ws").WebSocket|null, controller: import("ws").WebSocket|null, width: number, height: number }>} */
const sessions = new Map();

function send(ws, obj) {
  if (ws && ws.readyState === ws.OPEN) ws.send(JSON.stringify(obj));
}

wss.on("connection", (ws) => {
  ws.role = null;
  ws.code = null;

  ws.on("message", (data, isBinary) => {
    // Binary messages are always screen frames coming from the device;
    // relay them untouched to the paired controller.
    if (isBinary) {
      if (ws.role !== "device" || !ws.code) return;
      const session = sessions.get(ws.code);
      if (session?.controller) session.controller.send(data, { binary: true });
      return;
    }

    let msg;
    try {
      msg = JSON.parse(data.toString());
    } catch {
      return;
    }

    if (msg.type === "register") {
      handleRegister(ws, msg);
      return;
    }

    if (!ws.role || !ws.code) return;
    const session = sessions.get(ws.code);
    if (!session) return;

    if (msg.type === "info" && ws.role === "device") {
      session.width = msg.width;
      session.height = msg.height;
      send(session.controller, { type: "info", width: msg.width, height: msg.height });
      return;
    }

    // Control commands (tap/swipe/key/text) only make sense controller -> device.
    // Anything else (e.g. device status pings) is ignored to keep the relay dumb and safe.
    if (ws.role === "controller") {
      send(session.device, msg);
    }
  });

  ws.on("close", () => {
    if (!ws.code) return;
    const session = sessions.get(ws.code);
    if (!session) return;

    if (ws.role === "device") {
      send(session.controller, { type: "device-left" });
      sessions.delete(ws.code);
    } else if (ws.role === "controller") {
      session.controller = null;
      send(session.device, { type: "controller-left" });
    }
  });
});

function handleRegister(ws, msg) {
  const code = String(msg.code || "").trim();
  if (!/^\d{6}$/.test(code)) {
    send(ws, { type: "error", message: "invalid pairing code" });
    return;
  }

  if (msg.role === "device") {
    if (sessions.has(code) && sessions.get(code).device) {
      send(ws, { type: "error", message: "code already in use" });
      return;
    }
    sessions.set(code, { device: ws, controller: sessions.get(code)?.controller ?? null, width: 0, height: 0 });
    ws.role = "device";
    ws.code = code;
    send(ws, { type: "registered" });
    const session = sessions.get(code);
    if (session.controller) send(session.controller, { type: "device-joined" });
    return;
  }

  if (msg.role === "controller") {
    const session = sessions.get(code);
    if (!session || !session.device) {
      send(ws, { type: "error", message: "no device waiting with that code" });
      return;
    }
    if (session.controller && session.controller.readyState === session.controller.OPEN) {
      send(ws, { type: "error", message: "a controller is already connected" });
      return;
    }
    session.controller = ws;
    ws.role = "controller";
    ws.code = code;
    send(ws, { type: "registered", width: session.width, height: session.height });
    send(session.device, { type: "controller-joined" });
    return;
  }

  send(ws, { type: "error", message: "unknown role" });
}

server.listen(PORT, () => {
  console.log(`hp_remote relay server listening on http://localhost:${PORT}`);
});
