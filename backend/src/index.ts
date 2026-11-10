import express from 'express';
import http from 'http';
import { Server } from 'socket.io';
import cors from 'cors';
import dotenv from 'dotenv';
import crypto from 'crypto';
import { rateLimit } from 'express-rate-limit';
import { setupSocketHandlers } from './socketHandlers';
import { sessionManager } from './sessionManager';

dotenv.config();

const app = express();
app.disable('x-powered-by');
app.set('trust proxy', 1);

const server = http.createServer(app);
const io = new Server(server, {
  cors: {
    origin: process.env.CORS_ORIGIN || '*',
    methods: ['GET', 'POST']
  },
  maxHttpBufferSize: 256 * 1024 // 256KB signaling payload limit (file data never transits backend)
});

app.use((_req, res, next) => {
  res.setHeader('X-Content-Type-Options', 'nosniff');
  res.setHeader('X-Frame-Options', 'DENY');
  next();
});
app.use(cors({ origin: process.env.CORS_ORIGIN || '*' }));
app.use(express.json({ limit: '16kb' }));

const limiter = rateLimit({
  windowMs: 15 * 60 * 1000,
  limit: 100,
  standardHeaders: 'draft-7',
  legacyHeaders: false
});
app.use(limiter);

const turnLimiter = rateLimit({
  windowMs: 15 * 60 * 1000,
  limit: 30,
  standardHeaders: 'draft-7',
  legacyHeaders: false,
  message: { error: 'Too many TURN credential requests. Please retry later.' }
});

app.get('/health', (_req, res) => {
  res.status(200).json({ status: 'ok', uptime: process.uptime() });
});

app.get('/turn-credentials', turnLimiter, (req, res) => {
  const requestedSessionId =
    typeof req.query.sessionId === 'string'
      ? req.query.sessionId
      : typeof req.header('x-session-id') === 'string'
        ? req.header('x-session-id')
        : undefined;

  if (process.env.REQUIRE_SESSION_FOR_TURN === 'true') {
    const session = requestedSessionId ? sessionManager.getSessionById(requestedSessionId) : undefined;
    if (!session || session.status !== 'PAIRED') {
      return res.status(403).json({ error: 'Valid paired session required' });
    }
  } else if (requestedSessionId) {
    const session = sessionManager.getSessionById(requestedSessionId);
    if (!session) {
      return res.status(403).json({ error: 'Invalid or expired session' });
    }
  }

  // Use environment variables or local fallback for testing
  const turnSecret = process.env.TURN_SHARED_SECRET || 'dev-secret';
  const turnServer = process.env.TURN_SERVER_URL || 'turn:10.0.2.2:3478';
  if (process.env.NODE_ENV === 'production' && turnSecret === 'dev-secret') {
    console.warn('[TURN] WARNING: Using default TURN_SHARED_SECRET in production');
  }

  // Credentials valid for 1 hour (3600 seconds)
  const unixTimeStamp = Math.floor(Date.now() / 1000) + 3600;
  const username = `${unixTimeStamp}:transwire-user`;

  // Generate HMAC-SHA1 signature using the shared secret
  const hmac = crypto.createHmac('sha1', turnSecret);
  hmac.update(username);
  const password = hmac.digest('base64');

  console.log(`[TURN] Issued short-lived credentials for ${req.ip}`);

  res.setHeader('Cache-Control', 'no-store');
  res.json({
    uris: [turnServer],
    username,
    password
  });
});

setupSocketHandlers(io);

// Cleanup expired sessions every minute
setInterval(() => {
  sessionManager.cleanupExpiredSessions();
}, 60000);

const PORT = process.env.PORT || 3000;
server.listen(PORT, () => {
  console.log(`[Signaling Server] Listening on port ${PORT}`);
});
