import React, { useState } from 'react';
import {
  Smartphone,
  Layers,
  Lock,
  CheckCircle2,
  Send,
  Radio,
  Share2,
  UploadCloud,
  RotateCcw
} from 'lucide-react';
import { DashboardOverview } from '../types';
import { Card } from '../components/common/Card';
import { StatCard } from '../components/common/StatCard';
import { Badge } from '../components/common/Badge';
import { Button } from '../components/common/Button';
import { Modal } from '../components/common/Modal';
import { formatCurrency } from '../utils/formatters';
import { api } from '../services/api';

interface OverviewPageProps {
  overview: DashboardOverview | null;
  loading: boolean;
  isStale: boolean;
  isDisconnected: boolean;
  onRefresh: () => Promise<void>;
}

export const OverviewPage: React.FC<OverviewPageProps> = ({
  overview,
  loading,
  isStale,
  isDisconnected,
  onRefresh
}) => {
  const [actionLoading, setActionLoading] = useState<string | null>(null);
  const [actionMessage, setActionMessage] = useState<{ text: string; type: 'success' | 'error' } | null>(null);

  // Demo payment modal state
  const [demoModalOpen, setDemoModalOpen] = useState(false);
  const [senderVpa, setSenderVpa] = useState('alice@demo');
  const [receiverVpa, setReceiverVpa] = useState('bob@demo');
  const [amount, setAmount] = useState('50.00');
  const [pin, setPin] = useState('1234');
  const [startDevice, setStartDevice] = useState('phone-alice');

  const disabledControls = isStale || isDisconnected;

  const executeAction = async (name: string, fn: () => Promise<Record<string, unknown> | void>, successMsg: string) => {
    try {
      setActionLoading(name);
      setActionMessage(null);
      const res = await fn();
      const transfers = res && typeof res === 'object' && 'transfers' in res ? res.transfers : null;
      setActionMessage({
        text: `${successMsg} ${transfers != null ? `(${transfers} transfers)` : ''}`,
        type: 'success'
      });
      await onRefresh();
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : String(err);
      setActionMessage({
        text: `Error: ${msg}`,
        type: 'error'
      });
    } finally {
      setActionLoading(null);
    }
  };

  const handleSendDemoPayment = async (e: React.FormEvent) => {
    e.preventDefault();
    try {
      setActionLoading('send');
      const res = await api.sendDemoPayment({
        senderVpa,
        receiverVpa,
        amount: parseFloat(amount),
        pin,
        startDevice
      });
      setDemoModalOpen(false);
      setActionMessage({
        text: `Payment injected at ${res.injectedAt} (Packet: ${res.packetId.substring(0, 8)}...)`,
        type: 'success'
      });
      await onRefresh();
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : String(err);
      setActionMessage({ text: `Failed to inject: ${msg}`, type: 'error' });
    } finally {
      setActionLoading(null);
    }
  };

  if (loading && !overview) {
    return (
      <div className="space-y-6 animate-pulse">
        <div className="h-20 bg-slate-800/40 rounded-xl" />
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4">
          {[1, 2, 3, 4].map((i) => (
            <div key={i} className="h-28 bg-slate-800/40 rounded-xl" />
          ))}
        </div>
      </div>
    );
  }

  const statusVariant = (status?: string): 'healthy' | 'degraded' | 'disputed' | 'failed' | 'neutral' => {
    switch (status) {
      case 'HEALTHY': return 'healthy';
      case 'PARTITIONED': return 'degraded';
      case 'DEGRADED': return 'degraded';
      case 'DISPUTED': return 'disputed';
      case 'FAILED': return 'failed';
      default: return 'neutral';
    }
  };

  return (
    <div className="space-y-6">
      {/* Top Health Status Bar */}
      <div className="bg-[#161b22] border border-[#30363d] rounded-xl p-5 flex flex-col md:flex-row items-start md:items-center justify-between gap-4">
        <div>
          <div className="flex items-center space-x-3">
            <span className="text-sm font-medium text-slate-400">System State:</span>
            <Badge variant={statusVariant(overview?.systemStatus)} className="text-sm py-1 px-3">
              ● {overview?.systemStatus || 'UNKNOWN'}
            </Badge>
          </div>
          <p className="text-xs text-slate-400 mt-2">
            {overview?.systemStatus === 'HEALTHY' && 'All reachable nodes converged. Ledger invariants 100% verified.'}
            {overview?.systemStatus === 'PARTITIONED' && `${overview.severedLinkCount} severed link(s) active. Mesh isolated into submeshes.`}
            {overview?.systemStatus === 'DEGRADED' && `${overview.pendingGapCount} pending sequence gaps or transient retries active.`}
            {overview?.systemStatus === 'DISPUTED' && `${overview.disputedWallets} wallet(s) locked due to sequence counter collision / double-spend fork.`}
            {overview?.systemStatus === 'FAILED' && `${overview.invariantViolations} invariant violation(s) detected! Immediate audit required.`}
          </p>
        </div>

        <div className="flex flex-wrap items-center gap-3">
          <div className="text-right text-xs text-slate-400">
            <div>Reachable Convergence:</div>
            <div className="font-semibold text-slate-200">
              {overview?.meshConverged ? (
                <span className="text-emerald-400">✓ In-Sync (Digests Match)</span>
              ) : (
                <span className="text-amber-400">⚠ Divergent (Sync Needed)</span>
              )}
            </div>
          </div>
        </div>
      </div>

      {/* Action Notification */}
      {actionMessage && (
        <div
          className={`p-3 rounded-lg text-xs font-medium border flex items-center justify-between ${
            actionMessage.type === 'success'
              ? 'bg-emerald-950/70 border-emerald-800 text-emerald-300'
              : 'bg-rose-950/70 border-rose-800 text-rose-300'
          }`}
        >
          <span>{actionMessage.text}</span>
          <button
            onClick={() => setActionMessage(null)}
            className="text-slate-400 hover:text-slate-200 ml-4 font-bold"
          >
            ✕
          </button>
        </div>
      )}

      {/* Aggregate Stats Grid */}
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
        <StatCard
          label="Total Devices"
          value={`${overview?.totalDevices ?? 0} Nodes`}
          subtext={`${overview?.onlineBridges ?? 0} Bridge with 4G`}
          icon={<Smartphone className="w-5 h-5" />}
          indicatorColor="blue"
        />
        <StatCard
          label="Held Packets"
          value={`${overview?.totalHeldPackets ?? 0} in buffer`}
          subtext="Buffered in mesh devices"
          icon={<Layers className="w-5 h-5" />}
          indicatorColor="emerald"
        />
        <StatCard
          label="Escrow Locked"
          value={formatCurrency(overview?.totalEscrowBalance)}
          subtext={`Across ${overview?.activeWallets ?? 0} active wallets`}
          icon={<Lock className="w-5 h-5" />}
          indicatorColor="amber"
        />
        <StatCard
          label="Settled Ledger"
          value={`${overview?.settledTxCount ?? 0} settled`}
          subtext={`${overview?.conflictingTxCount ?? 0} conflicts | ${overview?.pendingGapCount ?? 0} gaps`}
          icon={<CheckCircle2 className="w-5 h-5" />}
          indicatorColor="emerald"
        />
      </div>

      {/* Quick Simulator Controls Card */}
      <Card
        title="🎬 Simulator Control Deck"
        subtitle="Trigger real mesh transport and anti-entropy operations across simulated nodes"
      >
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-5 gap-3">
          <Button
            variant="primary"
            onClick={() => setDemoModalOpen(true)}
            disabled={disabledControls}
            disabledReason="Controls disabled while data is stale or backend is disconnected"
            className="w-full flex items-center gap-2"
          >
            <Send className="w-4 h-4" />
            <span>Compose Payment</span>
          </Button>

          <Button
            variant="secondary"
            onClick={() => executeAction('gossip', () => api.triggerGossip(), 'Push gossip round executed')}
            loading={actionLoading === 'gossip'}
            disabled={disabledControls}
            disabledReason="Controls disabled while data is stale or backend is disconnected"
            className="w-full flex items-center gap-2"
          >
            <Radio className="w-4 h-4" />
            <span>Push Gossip</span>
          </Button>

          <Button
            variant="secondary"
            onClick={() =>
              executeAction(
                'sync',
                () => api.triggerAntiEntropy(),
                'Pairwise anti-entropy pull complete'
              )
            }
            loading={actionLoading === 'sync'}
            disabled={disabledControls}
            disabledReason="Controls disabled while data is stale or backend is disconnected"
            className="w-full flex items-center gap-2"
          >
            <Share2 className="w-4 h-4" />
            <span>Anti-Entropy Sync</span>
          </Button>

          <Button
            variant="secondary"
            onClick={() =>
              executeAction(
                'flush',
                () => api.flushBridges(),
                'Bridges uploaded all held packets to backend'
              )
            }
            loading={actionLoading === 'flush'}
            disabled={disabledControls}
            disabledReason="Controls disabled while data is stale or backend is disconnected"
            className="w-full flex items-center gap-2"
          >
            <UploadCloud className="w-4 h-4" />
            <span>Flush Bridges (4G)</span>
          </Button>

          <Button
            variant="danger"
            onClick={() => executeAction('reset', () => api.resetMesh(), 'Mesh buffers & cache reset')}
            loading={actionLoading === 'reset'}
            disabled={disabledControls}
            disabledReason="Controls disabled while data is stale or backend is disconnected"
            className="w-full flex items-center gap-2"
          >
            <RotateCcw className="w-4 h-4" />
            <span>Reset Mesh</span>
          </Button>
        </div>
      </Card>

      {/* Invariants & Health Banner */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
        <Card title="🛡️ Invariant Health Summary">
          <div className="space-y-3">
            <div className="flex items-center justify-between text-sm py-2 border-b border-[#30363d]">
              <span className="text-slate-300 font-medium">Machine-Checkable Invariants (I1–I12)</span>
              <Badge variant={overview?.invariantViolations === 0 ? 'healthy' : 'failed'}>
                {overview?.invariantViolations === 0 ? '12/12 PASSED' : `${overview?.invariantViolations} VIOLATIONS`}
              </Badge>
            </div>
            <div className="flex items-center justify-between text-sm py-2 border-b border-[#30363d]">
              <span className="text-slate-300 font-medium">Active Fault Rules in Engine</span>
              <span className="text-slate-200 font-bold">{overview?.activeFaultRules ?? 0} rules</span>
            </div>
            <div className="flex items-center justify-between text-sm py-2">
              <span className="text-slate-300 font-medium">Disputed / Forked Wallets</span>
              <Badge variant={overview?.disputedWallets === 0 ? 'healthy' : 'disputed'}>
                {overview?.disputedWallets ?? 0} disputed
              </Badge>
            </div>
          </div>
        </Card>

        <Card title="💰 Ledger Invariant Snapshot">
          <div className="space-y-3 text-sm">
            <div className="flex items-center justify-between py-2 border-b border-[#30363d]">
              <span className="text-slate-300 font-medium">Total Liquid Balances:</span>
              <span className="font-mono text-emerald-400 font-bold">{formatCurrency(overview?.totalLiquidBalance)}</span>
            </div>
            <div className="flex items-center justify-between py-2 border-b border-[#30363d]">
              <span className="text-slate-300 font-medium">Total Escrow Reserved:</span>
              <span className="font-mono text-amber-400 font-bold">{formatCurrency(overview?.totalEscrowBalance)}</span>
            </div>
            <div className="flex items-center justify-between py-2">
              <span className="text-slate-300 font-medium">Conserved Total Funds (I4):</span>
              <span className="font-mono text-slate-100 font-bold">
                {formatCurrency(
                  (Number(overview?.totalLiquidBalance || 0) + Number(overview?.totalEscrowBalance || 0)).toFixed(2)
                )}
              </span>
            </div>
          </div>
        </Card>
      </div>

      {/* Compose Payment Modal */}
      <Modal
        isOpen={demoModalOpen}
        onClose={() => setDemoModalOpen(false)}
        title="Compose & Inject Offline Payment"
      >
        <form onSubmit={handleSendDemoPayment} className="space-y-4">
          <p className="text-xs text-slate-400">
            Simulates a sender's phone creating an offline payment instruction, signing it with Ed25519,
            encrypting it with server RSA-OAEP / AES-256-GCM, and inserting it into the mesh buffer.
          </p>

          <div>
            <label className="block text-xs font-semibold text-slate-300 uppercase mb-1">Sender VPA</label>
            <select
              value={senderVpa}
              onChange={(e) => setSenderVpa(e.target.value)}
              className="w-full bg-[#0d1117] border border-[#30363d] rounded-lg px-3 py-2 text-sm text-slate-200"
            >
              <option value="alice@demo">alice@demo</option>
              <option value="bob@demo">bob@demo</option>
              <option value="carol@demo">carol@demo</option>
              <option value="dave@demo">dave@demo</option>
            </select>
          </div>

          <div>
            <label className="block text-xs font-semibold text-slate-300 uppercase mb-1">Receiver VPA</label>
            <select
              value={receiverVpa}
              onChange={(e) => setReceiverVpa(e.target.value)}
              className="w-full bg-[#0d1117] border border-[#30363d] rounded-lg px-3 py-2 text-sm text-slate-200"
            >
              <option value="bob@demo">bob@demo</option>
              <option value="alice@demo">alice@demo</option>
              <option value="carol@demo">carol@demo</option>
              <option value="dave@demo">dave@demo</option>
            </select>
          </div>

          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className="block text-xs font-semibold text-slate-300 uppercase mb-1">Amount (₹)</label>
              <input
                type="number"
                step="0.01"
                min="0.01"
                value={amount}
                onChange={(e) => setAmount(e.target.value)}
                className="w-full bg-[#0d1117] border border-[#30363d] rounded-lg px-3 py-2 text-sm text-slate-200"
                required
              />
            </div>
            <div>
              <label className="block text-xs font-semibold text-slate-300 uppercase mb-1">PIN</label>
              <input
                type="password"
                maxLength={4}
                value={pin}
                onChange={(e) => setPin(e.target.value)}
                className="w-full bg-[#0d1117] border border-[#30363d] rounded-lg px-3 py-2 text-sm text-slate-200"
                required
              />
            </div>
          </div>

          <div>
            <label className="block text-xs font-semibold text-slate-300 uppercase mb-1">Inject at Device</label>
            <select
              value={startDevice}
              onChange={(e) => setStartDevice(e.target.value)}
              className="w-full bg-[#0d1117] border border-[#30363d] rounded-lg px-3 py-2 text-sm text-slate-200"
            >
              <option value="phone-alice">phone-alice</option>
              <option value="phone-stranger1">phone-stranger1</option>
              <option value="phone-stranger2">phone-stranger2</option>
              <option value="phone-stranger3">phone-stranger3</option>
              <option value="phone-bridge">phone-bridge</option>
            </select>
          </div>

          <div className="pt-3 flex justify-end space-x-3">
            <Button variant="outline" type="button" onClick={() => setDemoModalOpen(false)}>
              Cancel
            </Button>
            <Button variant="primary" type="submit" loading={actionLoading === 'send'}>
              Sign & Inject into Mesh
            </Button>
          </div>
        </form>
      </Modal>
    </div>
  );
};
