import { Session } from './types';

class SessionManager {
  private sessions = new Map<string, Session>();
  private pinToSessionId = new Map<string, string>();

  createSession(session: Session) {
    this.sessions.set(session.sessionId, session);
    this.pinToSessionId.set(session.pin, session.sessionId);
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
    return this.getSessionById(sessionId);
  }

  updateSession(sessionId: string, updates: Partial<Session>) {
    const session = this.sessions.get(sessionId);
    if (session) {
      Object.assign(session, updates);
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
