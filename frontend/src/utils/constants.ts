import { FaultType } from '../types';

export const FAULT_TYPES: { type: FaultType; label: string; description: string }[] = [
  { type: 'DROP', label: 'Drop Packet / Sync Message', description: 'Silently discards packet or sync message' },
  { type: 'DUPLICATE', label: 'Duplicate Packet (Storm)', description: 'Duplicates transmission N times' },
  { type: 'DELAY', label: 'Delay Delivery', description: 'Withholds packet for delayed delivery' },
  { type: 'REORDER', label: 'Reorder Packets', description: 'Inverts delivery order of outgoing packets' },
  { type: 'PARTITION', label: 'Network Partition', description: 'Severs communication link between nodes' },
  { type: 'PEER_UNAVAILABLE', label: 'Peer Unavailable', description: 'Simulates peer unreachable during anti-entropy sync' },
  { type: 'BRIDGE_UNAVAILABLE', label: 'Bridge Transport Outage', description: 'Failure of 4G upload from mesh to backend' },
  { type: 'MALFORMED_SYNC_MESSAGE', label: 'Malformed Sync Message', description: 'Invalid schema or corrupted control message' },
  { type: 'CORRUPTED_PACKET_PAYLOAD', label: 'Corrupted Ciphertext Payload', description: 'Corrupts ciphertext causing decryption/auth failure' },
  { type: 'TRANSIENT_DATABASE_FAILURE', label: 'Transient Database Contention', description: 'Simulates OptimisticLockException on settlement' },
  { type: 'STALE_RESPONSE', label: 'Lost HTTP Response (Post-Commit)', description: 'Drops client response after ledger commit' },
  { type: 'DUPLICATE_REQUEST', label: 'Concurrent Duplicate Request', description: 'Simultaneous submissions of identical packet' },
  { type: 'CRASH_AND_RESTART', label: 'Crash & Reboot Node', description: 'Wipes volatile in-memory packet buffer' },
];

export const KNOWN_NODE_COORDINATES: Record<string, { x: number; y: number }> = {
  'phone-alice': { x: 160, y: 180 },
  'phone-stranger1': { x: 360, y: 70 },
  'phone-stranger2': { x: 560, y: 180 },
  'phone-stranger3': { x: 460, y: 340 },
  'phone-bridge': { x: 230, y: 340 }
};
