import express from 'express';
import http from 'http';
import { Server } from 'socket.io';
import cors from 'cors';
import dotenv from 'dotenv';
import { rateLimit } from 'express-rate-limit';
import { setupSocketHandlers } from './socketHandlers';
import { sessionManager } from './sessionManager';

dotenv.config();

const app = express();
const server = http.createServer(app);
const io = new Server(server, {
  cors: {
    origin: process.env.CORS_ORIGIN || '*',
    methods: ['GET', 'POST']
  },
  maxHttpBufferSize: 1e6 // 1MB payload limit
});

app.use(cors());
app.use(express.json());

const limiter = rateLimit({
  windowMs: 15 * 60 * 1000,
  limit: 100,
  standardHeaders: 'draft-7',
  legacyHeaders: false,
});
app.use(limiter);

app.get('/health', (req, res) => {
  res.status(200).json({ status: 'ok', uptime: process.uptime() });
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
