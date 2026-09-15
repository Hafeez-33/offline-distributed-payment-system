import React, { useState } from 'react';
import { Wallet, Plus } from 'lucide-react';
import { WalletSummaryState } from '../types';
import { WalletTable } from '../components/wallets/WalletTable';
import { AllocateModal } from '../components/wallets/AllocateModal';
import { Card } from '../components/common/Card';
import { Button } from '../components/common/Button';
import { formatCurrency } from '../utils/formatters';
import { api } from '../services/api';

interface WalletsPageProps {
  walletState: WalletSummaryState | null;
  loading: boolean;
  isStale: boolean;
  isDisconnected: boolean;
  onRefresh: () => Promise<void>;
}

export const WalletsPage: React.FC<WalletsPageProps> = ({
  walletState,
  loading,
  isStale,
  isDisconnected,
  onRefresh
}) => {
  const [allocateModalOpen, setAllocateModalOpen] = useState(false);
  const [reconcilingId, setReconcilingId] = useState<string | null>(null);
  const [actionNotice, setActionNotice] = useState<{ text: string; type: 'success' | 'error' } | null>(null);
  const [actionLoading, setActionLoading] = useState(false);

  const disabled = isStale || isDisconnected;

  if (loading && !walletState) {
    return (
      <div className="space-y-6 animate-pulse">
        <div className="h-16 bg-slate-800/40 rounded-xl" />
        <div className="h-64 bg-slate-800/40 rounded-xl" />
      </div>
    );
  }

  const handleAllocate = async (payload: { ownerVpa: string; amount: number; durationHours: number }) => {
    try {
      setActionLoading(true);
      const res = await api.allocateWallet(payload);
      setActionNotice({
        text: `Allocated ${formatCurrency(res.allocatedAmount)} to ${res.ownerVpa} (Wallet: ${res.walletId})`,
        type: 'success'
      });
      await onRefresh();
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : String(err);
      setActionNotice({ text: `Allocation failed: ${msg}`, type: 'error' });
    } finally {
      setActionLoading(false);
    }
  };

  const handleReconcile = async (walletId: string) => {
    try {
      setReconcilingId(walletId);
      const res = await api.reconcileWallet(walletId);
      setActionNotice({
        text: `Wallet ${res.walletId} successfully reconciled and unspent escrow returned to liquid balance.`,
        type: 'success'
      });
      await onRefresh();
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : String(err);
      setActionNotice({ text: `Reconciliation failed: ${msg}`, type: 'error' });
    } finally {
      setReconcilingId(null);
    }
  };

  return (
    <div className="space-y-6">
      {/* Header & Controls */}
      <div className="bg-[#161b22] border border-[#30363d] rounded-xl p-5 flex flex-col md:flex-row items-start md:items-center justify-between gap-4">
        <div>
          <h2 className="text-lg font-bold text-slate-100 flex items-center gap-2">
            <Wallet className="w-5 h-5 text-emerald-400" />
            <span>Escrowed Offline Wallets & Ledger Accounts</span>
          </h2>
          <p className="text-xs text-slate-400 mt-1">
            Authoritative escrow accounting guarantees double spending is mathematically impossible offline.
            Funds are locked on server before offline issuance.
          </p>
        </div>

        <Button
          variant="primary"
          onClick={() => setAllocateModalOpen(true)}
          disabled={disabled}
          className="flex items-center gap-2 text-xs"
        >
          <Plus className="w-4 h-4" />
          <span>Allocate Offline Escrow</span>
        </Button>
      </div>

      {actionNotice && (
        <div
          className={`p-3 rounded-lg text-xs font-medium border flex items-center justify-between ${
            actionNotice.type === 'success'
              ? 'bg-emerald-950 border-emerald-800 text-emerald-300'
              : 'bg-rose-950 border-rose-800 text-rose-300'
          }`}
        >
          <span>{actionNotice.text}</span>
          <button onClick={() => setActionNotice(null)} className="text-slate-400 hover:text-slate-200 ml-4">
            ✕
          </button>
        </div>
      )}

      {/* Wallets Table Card */}
      <Card
        title="Authoritative Offline Wallets"
        subtitle="Monotonic counters, epoch tracking, and active remaining escrow balance"
      >
        <WalletTable
          wallets={walletState?.wallets ?? []}
          onReconcile={handleReconcile}
          reconcilingId={reconcilingId}
          disabled={disabled}
        />
      </Card>

      {/* Underlying Bank Accounts Table Card */}
      <Card
        title="Core Ledger Accounts (Bank Balance)"
        subtitle="Liquid balances and locked escrow amounts for each registered VPA"
      >
        <div className="overflow-x-auto">
          <table className="w-full text-left text-xs text-slate-300">
            <thead className="bg-[#0d1117] text-[11px] text-slate-400 uppercase tracking-wider border-b border-[#30363d]">
              <tr>
                <th className="px-4 py-3">VPA</th>
                <th className="px-4 py-3">Account Holder</th>
                <th className="px-4 py-3">Liquid Balance</th>
                <th className="px-4 py-3">Offline Locked Escrow</th>
                <th className="px-4 py-3">Total Funds (I4)</th>
                <th className="px-4 py-3">Optimistic Version</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-[#30363d]">
              {walletState?.accounts.map((acc) => {
                const total = (Number(acc.liquidBalance || 0) + Number(acc.offlineLockedBalance || 0)).toFixed(2);
                return (
                  <tr key={acc.vpa} className="hover:bg-slate-800/30 transition-colors">
                    <td className="px-4 py-3 font-mono font-semibold text-slate-200">{acc.vpa}</td>
                    <td className="px-4 py-3 text-slate-300">{acc.holderName}</td>
                    <td className="px-4 py-3 font-mono text-emerald-400 font-medium">
                      {formatCurrency(acc.liquidBalance)}
                    </td>
                    <td className="px-4 py-3 font-mono text-amber-400">
                      {formatCurrency(acc.offlineLockedBalance)}
                    </td>
                    <td className="px-4 py-3 font-mono font-bold text-slate-100">
                      {formatCurrency(total)}
                    </td>
                    <td className="px-4 py-3 font-mono text-slate-400">v{acc.version}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      </Card>

      {/* Allocate Escrow Modal */}
      <AllocateModal
        isOpen={allocateModalOpen}
        onClose={() => setAllocateModalOpen(false)}
        onAllocate={handleAllocate}
        loading={actionLoading}
      />
    </div>
  );
};
