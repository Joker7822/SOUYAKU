import http from 'node:http';
import { WebSocketServer, WebSocket } from 'ws';
import { runSouyakuAgent, SOUYAKU_AI_MODEL } from './ai-agent.js';

const port = Number(process.env.PORT || 8787);
const rooms = new Map();
const clients = new WeakMap();
const aiRateLimits = new Map();
const AI_RATE_WINDOW_MS = 10 * 60 * 1000;
const AI_RATE_MAX = Number(process.env.SOUYAKU_AI_RATE_MAX || 30);

function clientAddress(req) {
  const forwarded = req.headers['x-forwarded-for'];
  if (typeof forwarded === 'string' && forwarded.trim()) return forwarded.split(',')[0].trim();
  return req.socket.remoteAddress || 'unknown';
}

function consumeAiRateLimit(req) {
  const now = Date.now();
  const key = clientAddress(req);
  const current = aiRateLimits.get(key);
  if (!current || now >= current.resetAt) {
    aiRateLimits.set(key, { count: 1, resetAt: now + AI_RATE_WINDOW_MS });
    return { allowed: true, remaining: Math.max(0, AI_RATE_MAX - 1) };
  }
  if (current.count >= AI_RATE_MAX) return { allowed: false, remaining: 0, resetAt: current.resetAt };
  current.count += 1;
  return { allowed: true, remaining: Math.max(0, AI_RATE_MAX - current.count) };
}

function jsonResponse(res, status, payload, extraHeaders = {}) {
  res.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'cache-control': 'no-store',
    ...extraHeaders,
  });
  res.end(JSON.stringify(payload));
}

async function readJsonBody(req, maxBytes = 96 * 1024) {
  const chunks = [];
  let size = 0;
  for await (const chunk of req) {
    size += chunk.length;
    if (size > maxBytes) {
      const error = new Error('Request body too large');
      error.code = 'body_too_large';
      throw error;
    }
    chunks.push(chunk);
  }
  if (chunks.length === 0) return {};
  try {
    return JSON.parse(Buffer.concat(chunks).toString('utf8'));
  } catch {
    const error = new Error('Invalid JSON');
    error.code = 'invalid_json';
    throw error;
  }
}

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

const server = http.createServer(async (req, res) => {
  if (req.method === 'POST' && req.url === '/ai/agent') {
    if (!process.env.OPENAI_API_KEY) {
      jsonResponse(res, 503, { ok: false, error: 'ai_not_configured', message: 'SOUYAKU Agent is not configured on this server.' });
      return;
    }
    const rate = consumeAiRateLimit(req);
    if (!rate.allowed) {
      const retryAfter = Math.max(1, Math.ceil((rate.resetAt - Date.now()) / 1000));
      jsonResponse(res, 429, { ok: false, error: 'rate_limited', message: 'Too many AI requests.' }, { 'retry-after': String(retryAfter) });
      return;
    }
    try {
      const body = await readJsonBody(req);
      const agentResult = await runSouyakuAgent(body);
      jsonResponse(res, 200, { ok: true, ...agentResult }, { 'x-ratelimit-remaining': String(rate.remaining) });
    } catch (error) {
      const code = error?.code || 'ai_error';
      const status = code === 'body_too_large' ? 413
        : code === 'invalid_json' || code === 'empty_conversation' ? 400
        : code === 'ai_timeout' ? 504
        : 502;
      console.error('SOUYAKU Agent error:', code, error?.message || error);
      jsonResponse(res, status, { ok: false, error: code, message: error?.message || 'AI request failed.' });
    }
    return;
  }

  if (req.method === 'GET' && req.url === '/health') {
    jsonResponse(res, 200, {
      ok: true,
      rooms: rooms.size,
      uptimeSeconds: Math.floor(process.uptime()),
      aiConfigured: Boolean(process.env.OPENAI_API_KEY),
      aiModel: SOUYAKU_AI_MODEL,
    });
    return;
  }
  if (req.method === 'GET' && (req.url === '/' || req.url === '')) {
    jsonResponse(res, 200, {
      service: 'SOUYAKU token relay',
      websocket: true,
      health: '/health',
      aiAgent: '/ai/agent',
    });
    return;
  }
  jsonResponse(res, 404, { error: 'not_found' });
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
  const now = Date.now();
  for (const [key, limit] of aiRateLimits.entries()) {
    if (now >= limit.resetAt) aiRateLimits.delete(key);
  }
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
