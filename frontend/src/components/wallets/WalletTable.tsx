import React from 'react';
import { OfflineWalletView } from '../../types';
import { Badge } from '../common/Badge';
import { Button } from '../common/Button';
import { formatCurrency } from '../../utils/formatters';

interface WalletTableProps {
  wallets: OfflineWalletView[];
  onReconcile: (walletId: string) => void;
  reconcilingId: string | null;
  disabled?: boolean;
}

export const WalletTable: React.FC<WalletTableProps> = ({
  wallets,
  onReconcile,
  reconcilingId,
  disabled = false
}) => {
  if (wallets.length === 0) {
    return (
      <div className="text-center py-10 text-xs text-slate-400 border border-dashed border-[#30363d] rounded-xl">
        No offline wallets allocated yet. Use "Allocate Offline Escrow" to create one.
      </div>
    );
  }

  const getStatusBadge = (status: string) => {
    switch (status) {
      case 'ACTIVE':
        return <Badge variant="healthy">ACTIVE</Badge>;
      case 'LOCKED_DISPUTED':
        return (
          <Badge variant="disputed" className="animate-pulse">
            ⚠ LOCKED_DISPUTED
          </Badge>
        );
      case 'AUDIT_REQUIRED':
        return <Badge variant="degraded">AUDIT_REQUIRED</Badge>;
      case 'RECONCILED_CLOSED':
        return <Badge variant="neutral">RECONCILED_CLOSED</Badge>;
      case 'EXPIRED':
        return <Badge variant="neutral">EXPIRED</Badge>;
      default:
        return <Badge>{status}</Badge>;
    }
  };

  return (
    <div className="overflow-x-auto">
      <table className="w-full text-left text-xs text-slate-300">
        <thead className="bg-[#0d1117] text-[11px] text-slate-400 uppercase tracking-wider border-b border-[#30363d]">
          <tr>
            <th className="px-4 py-3">Wallet ID</th>
            <th className="px-4 py-3">Owner VPA</th>
            <th className="px-4 py-3">Epoch</th>
            <th className="px-4 py-3">Allocated Escrow</th>
            <th className="px-4 py-3">Settled Amount</th>
            <th className="px-4 py-3">Remaining Escrow</th>
            <th className="px-4 py-3">Last Counter</th>
            <th className="px-4 py-3">Status</th>
            <th className="px-4 py-3 text-right">Actions</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-[#30363d]">
          {wallets.map((w) => {
            const isDisputed = w.status === 'LOCKED_DISPUTED';
            const canReconcile = w.status === 'ACTIVE' || w.status === 'EXPIRED';

            return (
              <tr
                key={w.walletId}
                className={`hover:bg-slate-800/30 transition-colors ${
                  isDisputed ? 'bg-rose-950/20' : ''
                }`}
              >
                <td className="px-4 py-3 font-mono font-semibold text-slate-200">
                  {w.walletId}
                </td>
                <td className="px-4 py-3 text-slate-300">{w.ownerVpa}</td>
                <td className="px-4 py-3 font-mono">Epoch {w.walletEpoch}</td>
                <td className="px-4 py-3 font-mono font-medium">
                  {formatCurrency(w.allocatedAmount)}
                </td>
                <td className="px-4 py-3 font-mono text-slate-400">
                  {formatCurrency(w.settledAmount)}
                </td>
                <td className="px-4 py-3 font-mono font-bold text-emerald-400">
                  {formatCurrency(w.remainingAmount)}
                </td>
                <td className="px-4 py-3 font-mono">#{w.lastSettledCounter}</td>
                <td className="px-4 py-3">{getStatusBadge(w.status)}</td>
                <td className="px-4 py-3 text-right">
                  {canReconcile ? (
                    <Button
                      variant="outline"
                      onClick={() => onReconcile(w.walletId)}
                      loading={reconcilingId === w.walletId}
                      disabled={disabled}
                      className="text-xs py-1 px-2.5"
                    >
                      Reconcile & Close
                    </Button>
                  ) : (
                    <span className="text-slate-400 text-[11px]">—</span>
                  )}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
};
