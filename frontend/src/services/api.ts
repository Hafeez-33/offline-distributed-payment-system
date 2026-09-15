import {
  DashboardOverview,
  MeshTopologyState,
  DeviceDetailView,
  WalletSummaryState,
  PaginatedTransactions,
  ReliabilityReportState,
  FaultRuleView,
  FaultRuleRequest
} from '../types';

const BASE_URL = '/api';

async function fetchJson<T>(url: string, options?: RequestInit): Promise<T> {
  const response = await fetch(url, {
    headers: {
      'Content-Type': 'application/json',
      ...options?.headers
    },
    ...options
  });

  if (!response.ok) {
    let errorMsg = `HTTP ${response.status}: ${response.statusText}`;
    try {
      const errJson = await response.json();
      if (errJson.error) errorMsg = errJson.error;
    } catch {
      // Ignored
    }
    throw new Error(errorMsg);
  }

  return response.json();
}

export const api = {
  // --- Dashboard Aggregates ---
  getOverview: (): Promise<DashboardOverview> =>
    fetchJson<DashboardOverview>(`${BASE_URL}/dashboard/overview`),

  getMeshSummary: (): Promise<MeshTopologyState> =>
    fetchJson<MeshTopologyState>(`${BASE_URL}/dashboard/mesh`),

  getDeviceDetail: (deviceId: string): Promise<DeviceDetailView> =>
    fetchJson<DeviceDetailView>(`${BASE_URL}/dashboard/mesh/devices/${encodeURIComponent(deviceId)}`),

  getWallets: (): Promise<WalletSummaryState> =>
    fetchJson<WalletSummaryState>(`${BASE_URL}/dashboard/wallets`),

  getTransactions: (
    page = 0,
    size = 25,
    status?: string,
    search?: string
  ): Promise<PaginatedTransactions> => {
    const params = new URLSearchParams({
      page: page.toString(),
      size: size.toString()
    });
    if (status && status !== 'ALL') params.append('status', status);
    if (search && search.trim()) params.append('search', search.trim());
    return fetchJson<PaginatedTransactions>(`${BASE_URL}/dashboard/transactions?${params.toString()}`);
  },

  getReliability: (): Promise<ReliabilityReportState> =>
    fetchJson<ReliabilityReportState>(`${BASE_URL}/dashboard/reliability`),

  // --- Fault Injection ---
  listFaultRules: (): Promise<FaultRuleView[]> =>
    fetchJson<FaultRuleView[]>(`${BASE_URL}/faults/rules`),

  registerFaultRule: (rule: FaultRuleRequest): Promise<{ status: string; faultId: string }> =>
    fetchJson<{ status: string; faultId: string }>(`${BASE_URL}/faults/rule`, {
      method: 'POST',
      body: JSON.stringify(rule)
    }),

  deleteFaultRule: (faultId: string): Promise<{ status: string; faultId: string }> =>
    fetchJson<{ status: string; faultId: string }>(`${BASE_URL}/faults/rule/${encodeURIComponent(faultId)}`, {
      method: 'DELETE'
    }),

  resetFaults: (): Promise<{ status: string }> =>
    fetchJson<{ status: string }>(`${BASE_URL}/faults/reset`, { method: 'POST' }),

  toggleFaults: (enabled: boolean): Promise<{ status: string; enabled: boolean }> =>
    fetchJson<{ status: string; enabled: boolean }>(`${BASE_URL}/faults/toggle`, {
      method: 'POST',
      body: JSON.stringify({ enabled })
    }),

  // --- Simulator Controls ---
  sendDemoPayment: (payload: {
    senderVpa: string;
    receiverVpa: string;
    amount: number;
    pin: string;
    ttl?: number;
    startDevice?: string;
  }): Promise<{ packetId: string; injectedAt: string }> =>
    fetchJson(`${BASE_URL}/demo/send`, {
      method: 'POST',
      body: JSON.stringify(payload)
    }),

  triggerGossip: (): Promise<{ transfers: number; deviceCounts: Record<string, number> }> =>
    fetchJson(`${BASE_URL}/mesh/gossip`, { method: 'POST' }),

  triggerAntiEntropy: (): Promise<{
    totalTransfers: number;
    sessionsInSync: number;
    sessionsRepaired: number;
    allReachableConverged: boolean;
  }> =>
    fetchJson(`${BASE_URL}/mesh/sync`, { method: 'POST' }),

  flushBridges: (): Promise<{ uploadsAttempted: number; results: Record<string, unknown>[] }> =>
    fetchJson(`${BASE_URL}/mesh/flush`, { method: 'POST' }),

  resetMesh: (): Promise<{ status: string }> =>
    fetchJson(`${BASE_URL}/mesh/reset`, { method: 'POST' }),

  partition: (payload: {
    nodeA?: string;
    nodeB?: string;
    submeshA?: string[];
    submeshB?: string[];
  }): Promise<{ status: string; severedLinks: string[] }> =>
    fetchJson(`${BASE_URL}/mesh/partition`, {
      method: 'POST',
      body: JSON.stringify(payload)
    }),

  heal: (payload?: { nodeA?: string; nodeB?: string }): Promise<{ status: string; severedLinks: string[] }> =>
    fetchJson(`${BASE_URL}/mesh/heal`, {
      method: 'POST',
      body: JSON.stringify(payload || {})
    }),

  // --- Wallets ---
  allocateWallet: (payload: {
    ownerVpa: string;
    amount: number;
    durationHours?: number;
  }): Promise<{ walletId: string; ownerVpa: string; allocatedAmount: number }> =>
    fetchJson(`${BASE_URL}/wallet/allocate`, {
      method: 'POST',
      body: JSON.stringify(payload)
    }),

  reconcileWallet: (walletId: string): Promise<{ walletId: string; status: string }> =>
    fetchJson(`${BASE_URL}/wallet/reconcile`, {
      method: 'POST',
      body: JSON.stringify({ walletId })
    })
};
