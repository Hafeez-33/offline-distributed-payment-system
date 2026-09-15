// --- Device & Mesh Models ---
export interface VirtualDeviceSummary {
  deviceId: string;
  hasInternet: boolean;
  stateDigest: string;
  packetCount: number;
}

export interface HeldPacketSummary {
  packetId: string;
  packetHash: string;
  ttl: number;
  createdAt: string | null;
}

export interface PeerSyncRecordView {
  peerId: string;
  lastSyncRound: number;
  inSync: boolean;
  consecutiveSuccesses: number;
}

export interface DeviceDetailView {
  deviceId: string;
  hasInternet: boolean;
  stateDigest: string;
  packetCount: number;
  heldPackets: HeldPacketSummary[];
  bucketChecksums: string[];
  peerSyncSummary: Record<string, PeerSyncRecordView>;
  walletState?: {
    walletId: string;
    walletEpoch: number;
    sequenceCounter: number;
    cumulativeSpend: string;
  } | null;
}

export interface MeshTopologyState {
  devices: VirtualDeviceSummary[];
  severedLinks: string[];
  allConverged: boolean;
}

// --- Wallet & Account Models ---
export type WalletStatus =
  | 'ACTIVE'
  | 'EXPIRED'
  | 'LOCKED_DISPUTED'
  | 'AUDIT_REQUIRED'
  | 'RECONCILED_CLOSED';

export interface OfflineWalletView {
  walletId: string;
  ownerVpa: string;
  allocatedAmount: number | string;
  settledAmount: number | string;
  remainingAmount: number | string;
  walletEpoch: number;
  lastSettledCounter: number;
  validUntil: string;
  status: WalletStatus;
}

export interface AccountView {
  vpa: string;
  holderName: string;
  liquidBalance: number | string;
  offlineLockedBalance: number | string;
  version: number;
}

export interface WalletSummaryState {
  wallets: OfflineWalletView[];
  accounts: AccountView[];
}

// --- Transaction Models ---
export type TransactionStatus =
  | 'SETTLED'
  | 'REJECTED'
  | 'CONFLICTING'
  | 'PENDING_SEQUENCE_GAP';

export interface TransactionView {
  id: number;
  packetHash: string;
  senderVpa: string;
  receiverVpa: string;
  amount: number | string;
  signedAt: string;
  settledAt: string | null;
  bridgeNodeId: string;
  hopCount: number;
  status: TransactionStatus;
  walletId: string | null;
  sequenceCounter: number | null;
  conflictReason: string | null;
  winningTransactionId: number | null;
  receiptSignature: string | null;
}

export interface PaginatedTransactions {
  content: TransactionView[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

// --- Reliability & Fault Models ---
export type FaultType =
  | 'DROP'
  | 'DUPLICATE'
  | 'DELAY'
  | 'REORDER'
  | 'PARTITION'
  | 'PEER_UNAVAILABLE'
  | 'BRIDGE_UNAVAILABLE'
  | 'MALFORMED_SYNC_MESSAGE'
  | 'CORRUPTED_PACKET_PAYLOAD'
  | 'TRANSIENT_DATABASE_FAILURE'
  | 'STALE_RESPONSE'
  | 'DUPLICATE_REQUEST'
  | 'CRASH_AND_RESTART';

export interface FaultRuleView {
  faultId: string;
  faultType: FaultType;
  sourceNode: string;
  destinationNode: string;
  occurrenceLimit: number;
  activations: number;
  packetHash: string | null;
  status: 'ACTIVE' | 'EXHAUSTED';
}

export interface FaultRuleRequest {
  faultType: string;
  sourceNode?: string;
  destinationNode?: string;
  occurrenceLimit?: number;
  delayMs?: number;
  packetHash?: string;
}

export interface ReliabilityMetricsView {
  faultInjectionsTotal: number;
  faultDropsTotal: number;
  faultDuplicatesTotal: number;
  faultDelaysTotal: number;
  faultReordersTotal: number;
  faultPartitionsTotal: number;
  faultRecoveriesTotal: number;
  retryAttemptsTotal: number;
  reconciliationRecoveryTotal: number;
  invariantViolationsTotal: number;
}

export interface InvariantCheckResult {
  id: string;
  name: string;
  status: 'PASSED' | 'FAILED' | 'WARNING';
  details: string;
}

export interface ReliabilityReportState {
  metrics: ReliabilityMetricsView;
  invariants: InvariantCheckResult[];
}

// --- Aggregate Overview Model ---
export type SystemHealthStatus =
  | 'HEALTHY'
  | 'PARTITIONED'
  | 'DEGRADED'
  | 'DISPUTED'
  | 'FAILED';

export interface DashboardOverview {
  systemStatus: SystemHealthStatus;
  totalDevices: number;
  onlineBridges: number;
  meshConverged: boolean;
  severedLinkCount: number;
  totalHeldPackets: number;
  totalAccounts: number;
  totalLiquidBalance: number | string;
  totalEscrowBalance: number | string;
  activeWallets: number;
  disputedWallets: number;
  settledTxCount: number;
  conflictingTxCount: number;
  pendingGapCount: number;
  activeFaultRules: number;
  invariantViolations: number;
  timestamp: string;
}
