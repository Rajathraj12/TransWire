import { z } from 'zod';

export interface Session {
  sessionId: string;
  pairingToken: string;
  pin: string;
  senderSocketId: string;
  receiverSocketId?: string;
  status: 'PENDING' | 'WAITING_APPROVAL' | 'PAIRED';
  expiresAt: number;
  failedAttempts: number;
  lastActivityAt: number;
}

const sessionIdSchema = z.string().min(10).max(100).regex(/^[A-Za-z0-9_-]+$/);
const tokenSchema = z.string().min(16).max(128);
const pinSchema = z.string().regex(/^\d{6}$/);

export const createSessionSchema = z.object({
  sessionId: sessionIdSchema,
  pairingToken: tokenSchema,
  pin: pinSchema
}).strict();

export const joinSessionSchema = z.object({
  sessionId: sessionIdSchema.optional(),
  pairingToken: tokenSchema.optional(),
  pin: pinSchema.optional()
}).strict().refine(data => (data.sessionId && data.pairingToken) || data.pin, {
  message: 'Either sessionId+pairingToken OR pin must be provided'
});

export const approveRejectSchema = z.object({
  sessionId: sessionIdSchema,
  receiverId: z.string().min(1).max(64)
}).strict();

export const sdpSignalSchema = z.object({
  sessionId: sessionIdSchema,
  sdp: z.string().min(1).max(100_000)
}).strict();

export const iceCandidateSchema = z.object({
  sessionId: sessionIdSchema,
  candidate: z.object({
    sdpMid: z.string().max(64).nullable().optional(),
    sdpMLineIndex: z.number().int().min(0).max(64),
    candidate: z.string().min(1).max(4096)
  }).strict()
}).strict();

export const leaveSessionSchema = z.object({
  sessionId: sessionIdSchema
}).strict();
