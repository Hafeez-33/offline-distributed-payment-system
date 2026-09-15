import React, { useState } from 'react';
import { Modal } from '../common/Modal';
import { Button } from '../common/Button';

interface AllocateModalProps {
  isOpen: boolean;
  onClose: () => void;
  onAllocate: (payload: { ownerVpa: string; amount: number; durationHours: number }) => Promise<void>;
  loading: boolean;
}

export const AllocateModal: React.FC<AllocateModalProps> = ({
  isOpen,
  onClose,
  onAllocate,
  loading
}) => {
  const [ownerVpa, setOwnerVpa] = useState('alice@demo');
  const [amount, setAmount] = useState('200.00');
  const [durationHours, setDurationHours] = useState('24');

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    await onAllocate({
      ownerVpa,
      amount: parseFloat(amount),
      durationHours: parseInt(durationHours, 10)
    });
    onClose();
  };

  return (
    <Modal isOpen={isOpen} onClose={onClose} title="Allocate Offline Wallet Escrow">
      <form onSubmit={handleSubmit} className="space-y-4">
        <p className="text-xs text-slate-400">
          Locks liquid bank funds into authoritative escrow, increments the wallet epoch,
          and issues a cryptographically signed <code>OfflineWalletCertificate</code>.
        </p>

        <div>
          <label className="block text-xs font-semibold text-slate-300 uppercase mb-1">
            Account Holder (VPA)
          </label>
          <select
            value={ownerVpa}
            onChange={(e) => setOwnerVpa(e.target.value)}
            className="w-full bg-[#0d1117] border border-[#30363d] rounded-lg px-3 py-2 text-sm text-slate-200"
          >
            <option value="alice@demo">alice@demo</option>
            <option value="bob@demo">bob@demo</option>
            <option value="carol@demo">carol@demo</option>
            <option value="dave@demo">dave@demo</option>
          </select>
        </div>

        <div>
          <label className="block text-xs font-semibold text-slate-300 uppercase mb-1">
            Escrow Amount (₹)
          </label>
          <input
            type="number"
            step="0.01"
            min="1.00"
            value={amount}
            onChange={(e) => setAmount(e.target.value)}
            className="w-full bg-[#0d1117] border border-[#30363d] rounded-lg px-3 py-2 text-sm text-slate-200"
            required
          />
        </div>

        <div>
          <label className="block text-xs font-semibold text-slate-300 uppercase mb-1">
            Validity Duration (Hours)
          </label>
          <input
            type="number"
            min="1"
            max="168"
            value={durationHours}
            onChange={(e) => setDurationHours(e.target.value)}
            className="w-full bg-[#0d1117] border border-[#30363d] rounded-lg px-3 py-2 text-sm text-slate-200"
            required
          />
        </div>

        <div className="pt-3 flex justify-end space-x-3">
          <Button variant="outline" type="button" onClick={onClose}>
            Cancel
          </Button>
          <Button variant="primary" type="submit" loading={loading}>
            Lock Escrow & Issue Certificate
          </Button>
        </div>
      </form>
    </Modal>
  );
};
