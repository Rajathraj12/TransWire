import crypto from 'crypto';
import { Session } from './types';

const MAX_ACTIVE_SESSIONS = 5000;
const MAX_FAILED_JOIN_ATTEMPTS_PER_SESSION = 5;

function timingSafeStringEqual(a: string, b: string): boolean {
  const hashA = crypto.createHash('sha256').update(a).digest();
  const hashB = crypto.createHash('sha256').update(b).digest();
  return crypto.timingSafeEqual(hashA, hashB);
}

class SessionManager {
  private sessions = new Map<string, Session>();
  private pinToSessionId = new Map<string, string>();

  createSession(session: Session): 'created' | 'rebound' | 'rejected' {
    this.cleanupExpiredSessions();
    if (this.sessions.size >= MAX_ACTIVE_SESSIONS) {
      return 'rejected';
    }

    const existing = this.sessions.get(session.sessionId);
    if (existing) {
      // Allow the same sender to re-bind its new socketId after brief backgrounding/reconnect
      if (
        existing.status !== 'PAIRED' &&
        timingSafeStringEqual(existing.pairingToken, session.pairingToken) &&
        existing.pin === session.pin
      ) {
        existing.senderSocketId = session.senderSocketId;
        existing.lastActivityAt = Date.now();
        return 'rebound';
      }
      return 'rejected';
    }

    // Ensure one active non-paired session per sender socket
    for (const [existingId, item] of this.sessions.entries()) {
      if (item.senderSocketId === session.senderSocketId && item.status !== 'PAIRED') {
        this.removeSession(existingId);
      }
    }

    this.sessions.set(session.sessionId, session);
    this.pinToSessionId.set(session.pin, session.sessionId);
    return 'created';
  }

  getSessionById(sessionId: string): Session | undefined {
    const session = this.sessions.get(sessionId);
    if (session && session.expiresAt < Date.now()) {
      this.removeSession(sessionId);
      return undefined;
    }
    return session;
  }

  getSessionByPin(pin: string): Session | undefined {
    const sessionId = this.pinToSessionId.get(pin);
    if (!sessionId) return undefined;
    const session = this.getSessionById(sessionId);
    if (!session || !timingSafeStringEqual(session.pin, pin)) {
      return undefined;
    }
    return session;
  }

  verifyPairingToken(session: Session, candidateToken: string): boolean {
    if (timingSafeStringEqual(session.pairingToken, candidateToken)) {
      return true;
    }
    this.recordFailedAttempt(session.sessionId);
    return false;
  }

  recordFailedAttempt(sessionId: string): boolean {
    const session = this.sessions.get(sessionId);
    if (!session) return false;
    session.failedAttempts += 1;
    if (session.failedAttempts >= MAX_FAILED_JOIN_ATTEMPTS_PER_SESSION) {
      this.removeSession(sessionId);
      return true; // Locked & purged
    }
    return false;
  }

  updateSession(sessionId: string, updates: Partial<Session>) {
    const session = this.sessions.get(sessionId);
    if (session) {
      Object.assign(session, updates, { lastActivityAt: Date.now() });
    }
  }

  removeSession(sessionId: string) {
    const session = this.sessions.get(sessionId);
    if (session) {
      this.pinToSessionId.delete(session.pin);
      this.sessions.delete(sessionId);
    }
  }

  cleanupExpiredSessions() {
    const now = Date.now();
    for (const [sessionId, session] of this.sessions.entries()) {
      if (session.expiresAt < now) {
        this.removeSession(sessionId);
      }
    }
  }

  getAllSessions() {
    return Array.from(this.sessions.values());
  }
}

export const sessionManager = new SessionManager();
