import { z } from 'zod';

export interface Session {
  sessionId: string;
  pairingToken: string;
  pin: string;
  senderSocketId: string;
  receiverSocketId?: string;
  status: 'PENDING' | 'WAITING_APPROVAL' | 'PAIRED';
  expiresAt: number;
}

export const createSessionSchema = z.object({
  sessionId: z.string().min(10).max(100),
  pairingToken: z.string().min(10).max(100),
  pin: z.string().length(6)
});

export const joinSessionSchema = z.object({
  sessionId: z.string().min(10).max(100).optional(),
  pairingToken: z.string().min(10).max(100).optional(),
  pin: z.string().length(6).optional()
}).refine(data => (data.sessionId && data.pairingToken) || data.pin, {
  message: "Either sessionId+pairingToken OR pin must be provided"
});

export const peerSignalSchema = z.object({
  sessionId: z.string(),
  signal: z.any()
});
