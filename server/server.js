import http from 'node:http';
import { WebSocketServer, WebSocket } from 'ws';

const port = Number(process.env.PORT || 8787);
const rooms = new Map();
const clients = new WeakMap();

function send(ws, payload) {
  if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(payload));
}

function validToken(value) {
  return typeof value === 'string' && /^[A-Z0-9]{6,12}$/.test(value);
}

function broadcastPeerCount(token) {
  const room = rooms.get(token);
  if (!room) return;
  const peerCount = room.size;
  for (const ws of room.values()) send(ws, { type: 'peer_count', peerCount });
}

function broadcastPeerLanguages(token) {
  const room = rooms.get(token);
  if (!room) return;
  for (const [clientId, ws] of room.entries()) {
    let peerLanguage = '';
    for (const [otherId, peerWs] of room.entries()) {
      if (otherId === clientId) continue;
      peerLanguage = String(clients.get(peerWs)?.language || '').slice(0, 32);
      break;
    }
    send(ws, { type: 'peer_language', language: peerLanguage });
  }
}

function leave(ws) {
  const meta = clients.get(ws);
  if (!meta) return;
  clients.delete(ws);
  const room = rooms.get(meta.token);
  if (!room) return;
  room.delete(meta.clientId);
  if (room.size === 0) rooms.delete(meta.token);
  else {
    broadcastPeerCount(meta.token);
    broadcastPeerLanguages(meta.token);
  }
}

const server = http.createServer((req, res) => {
  if (req.url === '/health') {
    res.writeHead(200, { 'content-type': 'application/json', 'cache-control': 'no-store' });
    res.end(JSON.stringify({ ok: true, rooms: rooms.size, uptimeSeconds: Math.floor(process.uptime()) }));
    return;
  }
  if (req.url === '/' || req.url === '') {
    res.writeHead(200, { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' });
    res.end(JSON.stringify({ service: 'SOUYAKU token relay', websocket: true, health: '/health' }));
    return;
  }
  res.writeHead(404, { 'content-type': 'application/json; charset=utf-8' });
  res.end(JSON.stringify({ error: 'not_found' }));
});

const wss = new WebSocketServer({ server, maxPayload: 16 * 1024, perMessageDeflate: false });

wss.on('connection', (ws) => {
  ws.isAlive = true;
  ws.on('pong', () => { ws.isAlive = true; });

  ws.on('message', (raw) => {
    let message;
    try {
      message = JSON.parse(raw.toString('utf8'));
    } catch {
      send(ws, { type: 'error', code: 'invalid_json', message: 'Invalid JSON' });
      return;
    }

    if (message.type === 'join') {
      if (!validToken(message.token) || typeof message.clientId !== 'string' || message.clientId.length > 100) {
        send(ws, { type: 'error', code: 'invalid_token', message: 'Invalid token or client ID' });
        return;
      }
      leave(ws);
      let room = rooms.get(message.token);
      if (!room) {
        room = new Map();
        rooms.set(message.token, room);
      }
      if (!room.has(message.clientId) && room.size >= 2) {
        send(ws, { type: 'error', code: 'room_full', message: 'Room already has two clients' });
        ws.close(1008, 'room full');
        return;
      }
      clients.set(ws, {
        token: message.token,
        clientId: message.clientId,
        language: String(message.language || '').slice(0, 32),
      });
      room.set(message.clientId, ws);
      send(ws, { type: 'joined', peerCount: room.size });
      broadcastPeerCount(message.token);
      broadcastPeerLanguages(message.token);
      return;
    }

    const meta = clients.get(ws);
    if (!meta) {
      send(ws, { type: 'error', code: 'join_required', message: 'Join first' });
      return;
    }

    if (message.type === 'language_update') {
      meta.language = String(message.language || '').slice(0, 32);
      broadcastPeerLanguages(meta.token);
      return;
    }

    if (message.type === 'utterance') {
      const text = typeof message.text === 'string' ? message.text.trim().slice(0, 4000) : '';
      const sourceLanguage = typeof message.sourceLanguage === 'string'
        ? message.sourceLanguage.slice(0, 32)
        : '';
      const targetLanguage = typeof message.targetLanguage === 'string'
        ? message.targetLanguage.slice(0, 32)
        : '';
      const translatedText = typeof message.translatedText === 'string'
        ? message.translatedText.trim().slice(0, 4000)
        : '';
      if (!text) return;
      const room = rooms.get(meta.token);
      if (!room) return;
      const payload = {
        type: 'utterance',
        senderId: meta.clientId,
        sequence: Number.isSafeInteger(message.sequence) ? message.sequence : 0,
        sourceLanguage,
        targetLanguage,
        text,
        translatedText,
      };
      for (const [clientId, peer] of room.entries()) {
        if (clientId !== meta.clientId) send(peer, payload);
      }
    }
  });

  ws.on('close', () => leave(ws));
  ws.on('error', () => leave(ws));
});

const heartbeat = setInterval(() => {
  for (const ws of wss.clients) {
    if (ws.isAlive === false) {
      leave(ws);
      ws.terminate();
      continue;
    }
    ws.isAlive = false;
    ws.ping();
  }
}, 30_000);

wss.on('close', () => clearInterval(heartbeat));

function shutdown(signal) {
  console.log(`${signal}: shutting down`);
  clearInterval(heartbeat);
  for (const ws of wss.clients) {
    try { ws.close(1001, 'server shutdown'); } catch {}
  }
  server.close(() => process.exit(0));
  setTimeout(() => process.exit(0), 5_000).unref();
}

process.once('SIGTERM', () => shutdown('SIGTERM'));
process.once('SIGINT', () => shutdown('SIGINT'));

server.listen(port, '0.0.0.0', () => {
  console.log(`SOUYAKU token relay listening on :${port}`);
});
