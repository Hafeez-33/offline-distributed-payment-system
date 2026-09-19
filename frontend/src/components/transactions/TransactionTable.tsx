import React from 'react';
import { TransactionView } from '../../types';
import { Badge } from '../common/Badge';
import { formatCurrency, formatTimestamp, truncateHash } from '../../utils/formatters';
import { ChevronRight } from 'lucide-react';

interface TransactionTableProps {
  transactions: TransactionView[];
  onSelectTx: (tx: TransactionView) => void;
}

export const TransactionTable: React.FC<TransactionTableProps> = ({
  transactions,
  onSelectTx
}) => {
  if (transactions.length === 0) {
    return (
      <div className="text-center py-12 text-xs text-slate-400 border border-dashed border-[#30363d] rounded-xl">
        No transactions match the selected filter.
      </div>
    );
  }

  const getStatusBadge = (status: string) => {
    switch (status) {
      case 'SETTLED':
        return <Badge variant="healthy">SETTLED</Badge>;
      case 'CONFLICTING':
        return <Badge variant="disputed">CONFLICTING</Badge>;
      case 'PENDING_SEQUENCE_GAP':
        return <Badge variant="degraded">PENDING_GAP</Badge>;
      case 'REJECTED':
        return <Badge variant="neutral">REJECTED</Badge>;
      default:
        return <Badge>{status}</Badge>;
    }
  };

  return (
    <div className="overflow-x-auto">
      <table className="w-full text-left text-xs text-slate-300">
        <thead className="bg-[#0d1117] text-[11px] text-slate-400 uppercase tracking-wider border-b border-[#30363d]">
          <tr>
            <th className="px-4 py-3">ID</th>
            <th className="px-4 py-3">Timestamp</th>
            <th className="px-4 py-3">Packet Hash</th>
            <th className="px-4 py-3">Sender & Receiver</th>
            <th className="px-4 py-3">Amount</th>
            <th className="px-4 py-3">Seq #</th>
            <th className="px-4 py-3">Hops</th>
            <th className="px-4 py-3">Status</th>
            <th className="px-4 py-3 text-right">Details</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-[#30363d]">
          {transactions.map((tx) => (
            <tr
              key={tx.id}
              onClick={() => onSelectTx(tx)}
              className="hover:bg-slate-800/40 cursor-pointer transition-colors"
            >
              <td className="px-4 py-3 font-mono font-bold text-slate-200">#{tx.id}</td>
              <td className="px-4 py-3 text-slate-400 whitespace-nowrap">
                {formatTimestamp(tx.settledAt || tx.signedAt)}
              </td>
              <td className="px-4 py-3 font-mono text-slate-300" title={tx.packetHash}>
                {truncateHash(tx.packetHash, 6, 6)}
              </td>
              <td className="px-4 py-3">
                <span className="text-slate-200 font-medium">{tx.senderVpa}</span>
                <span className="text-slate-400 mx-1.5">→</span>
                <span className="text-slate-200 font-medium">{tx.receiverVpa}</span>
              </td>
              <td className="px-4 py-3 font-mono font-bold text-emerald-400">
                {formatCurrency(tx.amount)}
              </td>
              <td className="px-4 py-3 font-mono">
                {tx.sequenceCounter != null ? `#${tx.sequenceCounter}` : '—'}
              </td>
              <td className="px-4 py-3 font-mono text-slate-400">
                {tx.bridgeNodeId} ({tx.hopCount}h)
              </td>
              <td className="px-4 py-3">{getStatusBadge(tx.status)}</td>
              <td className="px-4 py-3 text-right text-slate-400">
                <ChevronRight className="w-4 h-4 inline-block" />
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
};
