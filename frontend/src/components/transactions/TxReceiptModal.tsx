import React, { useState } from 'react';
import { Modal } from '../common/Modal';
import { TransactionView } from '../../types';
import { Check, Copy, ShieldCheck, AlertOctagon } from 'lucide-react';
import { formatCurrency, formatTimestamp } from '../../utils/formatters';

interface TxReceiptModalProps {
  tx: TransactionView | null;
  onClose: () => void;
}

export const TxReceiptModal: React.FC<TxReceiptModalProps> = ({ tx, onClose }) => {
  const [copied, setCopied] = useState<string | null>(null);

  if (!tx) return null;

  const copy = (val: string, key: string) => {
    navigator.clipboard.writeText(val);
    setCopied(key);
    setTimeout(() => setCopied(null), 2000);
  };

  const isConflict = tx.status === 'CONFLICTING';

  return (
    <Modal isOpen={!!tx} onClose={onClose} title={`Transaction #${tx.id} Details & Receipt`}>
      <div className="space-y-4 text-xs">
        {/* Settlement Status Banner */}
        <div
          className={`p-3 rounded-lg border flex items-center gap-2 ${
            isConflict
              ? 'bg-rose-950/60 border-rose-800 text-rose-300'
              : tx.status === 'SETTLED'
              ? 'bg-emerald-950/60 border-emerald-800 text-emerald-300'
              : 'bg-amber-950/60 border-amber-800 text-amber-300'
          }`}
        >
          {isConflict ? (
            <AlertOctagon className="w-5 h-5 text-rose-400 shrink-0" />
          ) : (
            <ShieldCheck className="w-5 h-5 text-emerald-400 shrink-0" />
          )}
          <div>
            <div className="font-bold text-sm">Status: {tx.status}</div>
            <div className="text-[11px] opacity-90">
              {isConflict
                ? 'Monotonic fork detected! Conflicting sequence counter.'
                : tx.status === 'SETTLED'
                ? 'Cryptographically verified & committed to authoritative ledger.'
                : 'Pending sequence gap resolution.'}
            </div>
          </div>
        </div>

        {/* Conflict Reason Details */}
        {isConflict && tx.conflictReason && (
          <div className="bg-rose-950/40 border border-rose-900 rounded-lg p-3 space-y-1">
            <span className="font-bold text-rose-400 uppercase text-[10px]">Conflict Diagnostic</span>
            <p className="text-slate-200">{tx.conflictReason}</p>
            {tx.winningTransactionId && (
              <p className="text-slate-400 text-[11px]">
                Prior Winning Transaction ID: <strong>#{tx.winningTransactionId}</strong>
              </p>
            )}
          </div>
        )}

        {/* Core Attributes Grid */}
        <div className="grid grid-cols-2 gap-3 bg-[#0d1117] border border-[#30363d] rounded-lg p-3">
          <div>
            <span className="text-slate-400">Sender VPA:</span>
            <div className="font-medium text-slate-200">{tx.senderVpa}</div>
          </div>
          <div>
            <span className="text-slate-400">Receiver VPA:</span>
            <div className="font-medium text-slate-200">{tx.receiverVpa}</div>
          </div>
          <div>
            <span className="text-slate-400">Amount:</span>
            <div className="font-mono text-emerald-400 font-bold text-sm">
              {formatCurrency(tx.amount)}
            </div>
          </div>
          <div>
            <span className="text-slate-400">Sequence Counter:</span>
            <div className="font-mono text-slate-200 font-bold">
              {tx.sequenceCounter != null ? `#${tx.sequenceCounter}` : 'Online (None)'}
            </div>
          </div>
          <div>
            <span className="text-slate-400">Delivery Route:</span>
            <div className="font-mono text-slate-300">
              {tx.bridgeNodeId} ({tx.hopCount} hops)
            </div>
          </div>
          <div>
            <span className="text-slate-400">Offline Wallet:</span>
            <div className="font-mono text-slate-300">{tx.walletId || 'Online Tx'}</div>
          </div>
          <div>
            <span className="text-slate-400">Signed Offline At:</span>
            <div className="text-slate-300">{formatTimestamp(tx.signedAt)}</div>
          </div>
          <div>
            <span className="text-slate-400">Backend Settled At:</span>
            <div className="text-slate-300">{formatTimestamp(tx.settledAt)}</div>
          </div>
        </div>

        {/* Packet Hash */}
        <div>
          <span className="font-semibold text-slate-400 uppercase text-[10px] block mb-1">
            Authoritative SHA-256 Packet Hash (Idempotency Key)
          </span>
          <div className="flex items-center justify-between bg-[#0d1117] border border-[#30363d] rounded-lg p-2 font-mono text-[11px] text-slate-200">
            <span className="select-all break-all mr-2">{tx.packetHash}</span>
            <button
              onClick={() => copy(tx.packetHash, 'hash')}
              className="text-slate-400 hover:text-slate-200 p-1"
            >
              {copied === 'hash' ? (
                <Check className="w-4 h-4 text-emerald-400" />
              ) : (
                <Copy className="w-4 h-4" />
              )}
            </button>
          </div>
        </div>

        {/* Cryptographic Receipt Signature */}
        {tx.receiptSignature && (
          <div>
            <span className="font-semibold text-slate-400 uppercase text-[10px] block mb-1">
              Bank Server Settlement Signature (Ed25519)
            </span>
            <div className="flex items-center justify-between bg-[#0d1117] border border-[#30363d] rounded-lg p-2 font-mono text-[11px] text-emerald-400">
              <span className="select-all break-all mr-2">{tx.receiptSignature}</span>
              <button
                onClick={() => copy(tx.receiptSignature!, 'sig')}
                className="text-slate-400 hover:text-slate-200 p-1"
              >
                {copied === 'sig' ? (
                  <Check className="w-4 h-4 text-emerald-400" />
                ) : (
                  <Copy className="w-4 h-4" />
                )}
              </button>
            </div>
          </div>
        )}
      </div>
    </Modal>
  );
};
