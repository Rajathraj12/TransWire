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

interface CloudflareIceServerEntry {
  urls?: string[] | string;
  username?: string;
  credential?: string;
}

interface CloudflareTurnResponse {
  iceServers?: CloudflareIceServerEntry[] | CloudflareIceServerEntry;
}

function extractTurnUrls(rawUrls?: string[] | string): string[] {
  const list = Array.isArray(rawUrls)
    ? rawUrls
    : typeof rawUrls === 'string'
      ? [rawUrls]
      : [];
  return list.filter((u) => u.startsWith('turn:') || u.startsWith('turns:'));
}

async function fetchCloudflareTurnCredentials(
  turnKeyId: string,
  apiToken: string,
  ttlSeconds: number
): Promise<{ uris: string[]; username: string; password: string }> {
  const endpoint = `https://rtc.live.cloudflare.com/v1/turn/keys/${encodeURIComponent(turnKeyId)}/credentials/generate-ice-servers`;
  const response = await fetch(endpoint, {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${apiToken}`,
      'Content-Type': 'application/json'
    },
    body: JSON.stringify({ ttl: ttlSeconds }),
    signal: AbortSignal.timeout(5000)
  });

  if (!response.ok) {
    throw new Error(`Cloudflare TURN API returned HTTP ${response.status}`);
  }

  const data = (await response.json()) as CloudflareTurnResponse;
  const entries = Array.isArray(data.iceServers)
    ? data.iceServers
    : data.iceServers
      ? [data.iceServers]
      : [];

  for (const entry of entries) {
    const uris = extractTurnUrls(entry.urls);
    if (entry.username && entry.credential && uris.length > 0) {
      return {
        uris,
        username: entry.username,
        password: entry.credential
      };
    }
  }

  throw new Error('Cloudflare TURN API response missing valid TURN credentials');
}

app.get('/health', (_req, res) => {
  res.status(200).json({ status: 'ok', uptime: process.uptime() });
});

app.get('/turn-credentials', turnLimiter, async (req, res) => {
  try {
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

    const cfTurnKeyId = (process.env.CLOUDFLARE_TURN_KEY_ID || process.env.CF_TURN_KEY_ID || '').trim();
    const cfTurnApiToken = (process.env.CLOUDFLARE_TURN_API_TOKEN || process.env.CF_TURN_API_TOKEN || '').trim();
    const ttlRaw = Number(process.env.CLOUDFLARE_TURN_TTL_SECONDS || 14400);
    const cfTtlSeconds = Number.isFinite(ttlRaw) ? Math.min(Math.max(Math.floor(ttlRaw), 60), 86400) : 14400;

    let cloudflareAttemptedAndFailed = false;
    if (cfTurnKeyId && cfTurnApiToken) {
      try {
        const cfCredentials = await fetchCloudflareTurnCredentials(cfTurnKeyId, cfTurnApiToken, cfTtlSeconds);
        console.log(`[TURN] Issued Cloudflare short-lived credentials for ${req.ip}`);
        res.setHeader('Cache-Control', 'no-store');
        return res.json(cfCredentials);
      } catch (err) {
        cloudflareAttemptedAndFailed = true;
        const detail = err instanceof Error ? err.message : String(err);
        console.warn(`[TURN] Cloudflare credential fetch failed (${detail}); checking fallback TURN configuration`);
      }
    }

    const turnUrlsRaw = process.env.TURN_SERVER_URLS || process.env.TURN_SERVER_URL || '';
    const uris = turnUrlsRaw
      .split(',')
      .map((u) => u.trim())
      .filter((u) => u.length > 0);

    if (uris.length === 0) {
      if (cloudflareAttemptedAndFailed) {
        return res.status(502).json({ error: 'Failed to generate TURN credentials' });
      }
      res.setHeader('Cache-Control', 'no-store');
      return res.json({
        uris: [],
        username: '',
        password: ''
      });
    }

    // Option A: Static TURN credentials (e.g., Metered / ExpressTURN fallback)
    const staticUsername = process.env.TURN_USERNAME?.trim();
    const staticPassword = process.env.TURN_PASSWORD?.trim();
    if (staticUsername && staticPassword) {
      console.log(`[TURN] Issued static relay configuration for ${req.ip}`);
      res.setHeader('Cache-Control', 'no-store');
      return res.json({
        uris,
        username: staticUsername,
        password: staticPassword
      });
    }

    // Option B: Ephemeral Coturn HMAC credentials (use-auth-secret)
    const turnSecret = process.env.TURN_SHARED_SECRET || 'dev-secret';
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
    return res.json({
      uris,
      username,
      password
    });
  } catch (err) {
    const detail = err instanceof Error ? err.message : String(err);
    console.error(`[TURN] Unexpected error issuing credentials: ${detail}`);
    return res.status(500).json({ error: 'Internal error generating TURN credentials' });
  }
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
