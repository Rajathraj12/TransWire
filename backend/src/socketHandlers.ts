import { Server, Socket } from 'socket.io';
import { sessionManager } from './sessionManager';
import {
  approveRejectSchema,
  createSessionSchema,
  iceCandidateSchema,
  joinSessionSchema,
  leaveSessionSchema,
  rejoinSessionSchema,
  retryTransferSchema,
  sdpSignalSchema
} from './types';

const PAIRING_TTL_MS = 2 * 60 * 1000;
const PAIRED_TTL_MS = 30 * 60 * 1000;

interface RateEntry {
  count: number;
  resetAt: number;
}

class SocketAbuseGuard {
  private counters = new Map<string, RateEntry>();
  private blockedIps = new Map<string, number>();
  private failedPinAttempts = new Map<string, RateEntry>();

  isBlocked(ip: string): boolean {
    const until = this.blockedIps.get(ip);
    if (!until) return false;
    if (until < Date.now()) {
      this.blockedIps.delete(ip);
      return false;
    }
    return true;
  }

  allowEvent(key: string, maxRequests: number, windowMs: number): boolean {
    const now = Date.now();
    const entry = this.counters.get(key);
    if (!entry || entry.resetAt <= now) {
      this.counters.set(key, { count: 1, resetAt: now + windowMs });
      return true;
    }
    if (entry.count >= maxRequests) {
      return false;
    }
    entry.count += 1;
    return true;
  }

  recordFailedJoin(ip: string) {
    const now = Date.now();
    const entry = this.failedPinAttempts.get(ip);
    if (!entry || entry.resetAt <= now) {
      this.failedPinAttempts.set(ip, { count: 1, resetAt: now + 5 * 60 * 1000 });
      return;
    }
    entry.count += 1;
    if (entry.count >= 8) {
      this.blockedIps.set(ip, now + 5 * 60 * 1000);
      this.failedPinAttempts.delete(ip);
    }
  }

  cleanup() {
    const now = Date.now();
    for (const [k, v] of this.counters.entries()) {
      if (v.resetAt <= now) this.counters.delete(k);
    }
    for (const [k, v] of this.failedPinAttempts.entries()) {
      if (v.resetAt <= now) this.failedPinAttempts.delete(k);
    }
    for (const [ip, until] of this.blockedIps.entries()) {
      if (until <= now) this.blockedIps.delete(ip);
    }
  }
}

const abuseGuard = new SocketAbuseGuard();
setInterval(() => abuseGuard.cleanup(), 60_000).unref();

function getClientIp(socket: Socket): string {
  return socket.handshake.address || 'unknown';
}

export function setupSocketHandlers(io: Server) {
  io.on('connection', (socket: Socket) => {
    const clientIp = getClientIp(socket);
    if (abuseGuard.isBlocked(clientIp)) {
      socket.disconnect(true);
      return;
    }

    console.log(`[Socket] Connected: ${socket.id}`);

    socket.on('create-session', (data, callback) => {
      if (!abuseGuard.allowEvent(`create:${clientIp}`, 10, 60_000)) {
        return callback?.({ success: false, error: 'Too many sessions created. Please wait.' });
      }
      try {
        const parsed = createSessionSchema.parse(data);
        const now = Date.now();
        const result = sessionManager.createSession({
          sessionId: parsed.sessionId,
          pairingToken: parsed.pairingToken,
          pin: parsed.pin,
          senderSocketId: socket.id,
          status: 'PENDING',
          expiresAt: now + PAIRING_TTL_MS,
          failedAttempts: 0,
          lastActivityAt: now
        });

        if (result === 'rejected') {
          return callback?.({ success: false, error: 'Unable to create session' });
        }

        socket.join(parsed.sessionId);
        console.log(`[Session] ${result === 'rebound' ? 'Rebound' : 'Created'} ${parsed.sessionId} by ${socket.id}`);

        // If a receiver requested to join while the sender briefly reconnected (e.g., sharing QR), notify now
        const activeSession = sessionManager.getSessionById(parsed.sessionId);
        if (result === 'rebound' && activeSession?.status === 'WAITING_APPROVAL' && activeSession.receiverSocketId) {
          socket.emit('join-request', { receiverId: activeSession.receiverSocketId });
        }

        callback?.({ success: true, sessionId: parsed.sessionId });
      } catch {
        callback?.({ success: false, error: 'Invalid data' });
      }
    });

    socket.on('join-session', (data, callback) => {
      if (abuseGuard.isBlocked(clientIp) || !abuseGuard.allowEvent(`join:${clientIp}`, 12, 60_000)) {
        return callback?.({ success: false, error: 'Too many join attempts. Please try again later.' });
      }
      try {
        const parsed = joinSessionSchema.parse(data);
        let session;

        if (parsed.pin) {
          session = sessionManager.getSessionByPin(parsed.pin);
        } else if (parsed.sessionId && parsed.pairingToken) {
          const candidate = sessionManager.getSessionById(parsed.sessionId);
          if (candidate && sessionManager.verifyPairingToken(candidate, parsed.pairingToken)) {
            session = candidate;
          }
        }

        if (!session) {
          abuseGuard.recordFailedJoin(clientIp);
          return callback?.({ success: false, error: 'Session not found, expired, or invalid' });
        }
        if (session.senderSocketId === socket.id) {
          return callback?.({ success: false, error: 'Sender cannot join own session' });
        }
        if (session.status !== 'PENDING') {
          return callback?.({ success: false, error: 'Session not available' });
        }

        sessionManager.updateSession(session.sessionId, {
          receiverSocketId: socket.id,
          status: 'WAITING_APPROVAL'
        });

        io.to(session.senderSocketId).emit('join-request', {
          receiverId: socket.id
        });

        console.log(`[Session] Join request for ${session.sessionId} by ${socket.id}`);
        callback?.({ success: true, sessionId: session.sessionId });
      } catch {
        callback?.({ success: false, error: 'Invalid data' });
      }
    });

    socket.on('approve-peer', (data) => {
      if (!abuseGuard.allowEvent(`ctrl:${socket.id}`, 20, 60_000)) return;
      try {
        const { sessionId, receiverId } = approveRejectSchema.parse(data);
        const session = sessionManager.getSessionById(sessionId);

        if (
          session &&
          session.senderSocketId === socket.id &&
          session.receiverSocketId === receiverId &&
          session.status === 'WAITING_APPROVAL'
        ) {
          sessionManager.updateSession(sessionId, {
            status: 'PAIRED',
            expiresAt: Date.now() + PAIRED_TTL_MS
          });

          const receiverSocket = io.sockets.sockets.get(receiverId);
          if (receiverSocket) {
            receiverSocket.join(sessionId);
            io.to(session.senderSocketId).emit('peer-joined', { sessionId });
            receiverSocket.emit('peer-joined', { sessionId, pairingToken: session.pairingToken });
            console.log(`[Session] Paired ${sessionId}`);
          } else {
            sessionManager.updateSession(sessionId, {
              status: 'PENDING',
              receiverSocketId: undefined
            });
          }
        }
      } catch {
        // Ignore invalid control payloads
      }
    });

    socket.on('reject-peer', (data) => {
      if (!abuseGuard.allowEvent(`ctrl:${socket.id}`, 20, 60_000)) return;
      try {
        const { sessionId, receiverId } = approveRejectSchema.parse(data);
        const session = sessionManager.getSessionById(sessionId);

        if (
          session &&
          session.senderSocketId === socket.id &&
          session.receiverSocketId === receiverId &&
          session.status === 'WAITING_APPROVAL'
        ) {
          sessionManager.updateSession(sessionId, {
            status: 'PENDING',
            receiverSocketId: undefined
          });
          io.to(receiverId).emit('join-rejected', { reason: 'Sender rejected the connection' });
        }
      } catch {
        // Ignore invalid control payloads
      }
    });

    socket.on('leave-session', (data) => {
      try {
        const { sessionId } = leaveSessionSchema.parse(data);
        const session = sessionManager.getSessionById(sessionId);
        if (session && (socket.id === session.senderSocketId || socket.id === session.receiverSocketId)) {
          socket.to(sessionId).emit('peer-disconnected');
          sessionManager.removeSession(sessionId);
          socket.leave(sessionId);
        }
      } catch {
        // Ignore invalid payloads
      }
    });

    socket.on('offer', (data) => {
      if (!abuseGuard.allowEvent(`signal:${socket.id}`, 120, 60_000)) return;
      try {
        const { sessionId, sdp } = sdpSignalSchema.parse(data);
        const session = sessionManager.getSessionById(sessionId);
        if (
          session &&
          session.status === 'PAIRED' &&
          (socket.id === session.senderSocketId || socket.id === session.receiverSocketId)
        ) {
          sessionManager.updateSession(sessionId, { expiresAt: Date.now() + PAIRED_TTL_MS });
          socket.to(sessionId).emit('offer', { sdp });
        }
      } catch {
        // Reject malformed signaling payloads silently
      }
    });

    socket.on('answer', (data) => {
      if (!abuseGuard.allowEvent(`signal:${socket.id}`, 120, 60_000)) return;
      try {
        const { sessionId, sdp } = sdpSignalSchema.parse(data);
        const session = sessionManager.getSessionById(sessionId);
        if (
          session &&
          session.status === 'PAIRED' &&
          (socket.id === session.senderSocketId || socket.id === session.receiverSocketId)
        ) {
          sessionManager.updateSession(sessionId, { expiresAt: Date.now() + PAIRED_TTL_MS });
          socket.to(sessionId).emit('answer', { sdp });
        }
      } catch {
        // Reject malformed signaling payloads silently
      }
    });

    socket.on('ice-candidate', (data) => {
      if (!abuseGuard.allowEvent(`signal:${socket.id}`, 240, 60_000)) return;
      try {
        const { sessionId, candidate } = iceCandidateSchema.parse(data);
        const session = sessionManager.getSessionById(sessionId);
        if (
          session &&
          session.status === 'PAIRED' &&
          (socket.id === session.senderSocketId || socket.id === session.receiverSocketId)
        ) {
          socket.to(sessionId).emit('ice-candidate', { candidate });
        }
      } catch {
        // Reject malformed ICE payloads silently
      }
    });

    socket.on('rejoin-session', (data, callback) => {
      if (!abuseGuard.allowEvent(`ctrl:${socket.id}`, 30, 60_000)) {
        return callback?.({ success: false, error: 'Too many rejoin requests' });
      }
      try {
        const parsed = rejoinSessionSchema.parse(data);
        const session = sessionManager.getSessionById(parsed.sessionId);
        if (!session || session.status !== 'PAIRED') {
          return callback?.({ success: false, error: 'Session not active' });
        }
        if (!sessionManager.verifyPairingToken(session, parsed.pairingToken)) {
          abuseGuard.recordFailedJoin(clientIp);
          return callback?.({ success: false, error: 'Invalid pairing token' });
        }

        sessionManager.updateSession(parsed.sessionId, {
          ...(parsed.isSender ? { senderSocketId: socket.id } : { receiverSocketId: socket.id }),
          expiresAt: Date.now() + PAIRED_TTL_MS
        });
        socket.join(parsed.sessionId);
        console.log(`[Session] Rejoined ${parsed.sessionId} (${parsed.isSender ? 'sender' : 'receiver'}) as ${socket.id}`);
        callback?.({ success: true });
      } catch {
        callback?.({ success: false, error: 'Invalid rejoin data' });
      }
    });

    socket.on('retry-transfer', (data) => {
      if (!abuseGuard.allowEvent(`ctrl:${socket.id}`, 20, 60_000)) return;
      try {
        const { sessionId } = retryTransferSchema.parse(data);
        const session = sessionManager.getSessionById(sessionId);
        if (
          session &&
          session.status === 'PAIRED' &&
          (socket.id === session.senderSocketId || socket.id === session.receiverSocketId)
        ) {
          sessionManager.updateSession(sessionId, { expiresAt: Date.now() + PAIRED_TTL_MS });
          socket.to(sessionId).emit('retry-transfer', { sessionId });
          console.log(`[Session] Retry requested on ${sessionId}`);
        }
      } catch {
        // Ignore invalid retry payloads
      }
    });

    socket.on('disconnect', () => {
      console.log(`[Socket] Disconnected: ${socket.id}`);
      sessionManager.getAllSessions().forEach((session) => {
        if (session.receiverSocketId === socket.id && session.status === 'WAITING_APPROVAL') {
          sessionManager.updateSession(session.sessionId, {
            status: 'PENDING',
            receiverSocketId: undefined
          });
        }
        // Keep PENDING and PAIRED sessions alive until expiresAt or explicit leave-session so brief
        // mobile WebSocket drops during transfers or retries do not break active pairs.
      });
    });
  });
}
