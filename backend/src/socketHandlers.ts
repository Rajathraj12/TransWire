import { Server, Socket } from 'socket.io';
import { sessionManager } from './sessionManager';
import { createSessionSchema, joinSessionSchema } from './types';

export function setupSocketHandlers(io: Server) {
  io.on('connection', (socket: Socket) => {
    console.log(`[Socket] Connected: ${socket.id}`);

    socket.on('create-session', (data, callback) => {
      try {
        const parsed = createSessionSchema.parse(data);
        const expiresAt = Date.now() + 2 * 60 * 1000; // 2 minutes

        sessionManager.createSession({
          sessionId: parsed.sessionId,
          pairingToken: parsed.pairingToken,
          pin: parsed.pin,
          senderSocketId: socket.id,
          status: 'PENDING',
          expiresAt
        });

        socket.join(parsed.sessionId);
        console.log(`[Session] Created ${parsed.sessionId} (PIN: ***) by ${socket.id}`);
        if(callback) callback({ success: true });
      } catch (e) {
        if(callback) callback({ success: false, error: 'Invalid data' });
      }
    });

    socket.on('join-session', (data, callback) => {
      try {
        const parsed = joinSessionSchema.parse(data);
        let session;

        if (parsed.pin) {
          session = sessionManager.getSessionByPin(parsed.pin);
        } else if (parsed.sessionId && parsed.pairingToken) {
          session = sessionManager.getSessionById(parsed.sessionId);
          if (session && session.pairingToken !== parsed.pairingToken) {
            session = undefined; // Invalid token
          }
        }

        if (!session) {
          return callback && callback({ success: false, error: 'Session not found or invalid' });
        }
        if (session.status !== 'PENDING') {
          return callback && callback({ success: false, error: 'Session not available' });
        }

        sessionManager.updateSession(session.sessionId, {
          receiverSocketId: socket.id,
          status: 'WAITING_APPROVAL'
        });

        // Notify sender
        io.to(session.senderSocketId).emit('join-request', {
           receiverId: socket.id
        });

        console.log(`[Session] Join request for ${session.sessionId} by ${socket.id}`);
        if(callback) callback({ success: true, sessionId: session.sessionId });
      } catch (e) {
        if(callback) callback({ success: false, error: 'Invalid data' });
      }
    });

    socket.on('approve-peer', (data) => {
      const { sessionId, receiverId } = data;
      const session = sessionManager.getSessionById(sessionId);

      if (session && session.senderSocketId === socket.id && session.status === 'WAITING_APPROVAL') {
        sessionManager.updateSession(sessionId, { status: 'PAIRED' });

        const receiverSocket = io.sockets.sockets.get(receiverId);
        if (receiverSocket) {
          receiverSocket.join(sessionId);
          io.to(sessionId).emit('peer-joined', { sessionId });
          console.log(`[Session] Paired ${sessionId}`);
        }
      }
    });

    socket.on('reject-peer', (data) => {
      const { sessionId, receiverId } = data;
      const session = sessionManager.getSessionById(sessionId);

      if (session && session.senderSocketId === socket.id && session.status === 'WAITING_APPROVAL') {
        sessionManager.updateSession(sessionId, { status: 'PENDING', receiverSocketId: undefined });
        io.to(receiverId).emit('join-rejected', { reason: 'Sender rejected the connection' });
      }
    });

    socket.on('offer', (data) => {
      const { sessionId, sdp } = data;
      const session = sessionManager.getSessionById(sessionId);
      if (session && (socket.id === session.senderSocketId || socket.id === session.receiverSocketId)) {
        socket.to(sessionId).emit('offer', { sdp });
      }
    });

    socket.on('answer', (data) => {
      const { sessionId, sdp } = data;
      const session = sessionManager.getSessionById(sessionId);
      if (session && (socket.id === session.senderSocketId || socket.id === session.receiverSocketId)) {
        socket.to(sessionId).emit('answer', { sdp });
      }
    });

    socket.on('ice-candidate', (data) => {
      const { sessionId, candidate } = data;
      const session = sessionManager.getSessionById(sessionId);
      if (session && (socket.id === session.senderSocketId || socket.id === session.receiverSocketId)) {
        socket.to(sessionId).emit('ice-candidate', { candidate });
      }
    });

    socket.on('disconnect', () => {
      console.log(`[Socket] Disconnected: ${socket.id}`);
      sessionManager.getAllSessions().forEach((session) => {
        if (session.senderSocketId === socket.id || session.receiverSocketId === socket.id) {
          socket.to(session.sessionId).emit('peer-disconnected');
          sessionManager.removeSession(session.sessionId);
        }
      });
    });
  });
}
