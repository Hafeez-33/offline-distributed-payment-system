import React, { useState } from 'react';
import { Modal } from '../common/Modal';
import { Button } from '../common/Button';
import { FaultRuleRequest } from '../../types';
import { FAULT_TYPES } from '../../utils/constants';

interface InjectFaultModalProps {
  isOpen: boolean;
  onClose: () => void;
  onInject: (rule: FaultRuleRequest) => Promise<void>;
  loading: boolean;
}

export const InjectFaultModal: React.FC<InjectFaultModalProps> = ({
  isOpen,
  onClose,
  onInject,
  loading
}) => {
  const [faultType, setFaultType] = useState('TRANSIENT_DATABASE_FAILURE');
  const [sourceNode, setSourceNode] = useState('*');
  const [destinationNode, setDestinationNode] = useState('*');
  const [occurrenceLimit, setOccurrenceLimit] = useState(1);
  const [delayMs, setDelayMs] = useState(0);
  const [packetHash, setPacketHash] = useState('');
  const [validationError, setValidationError] = useState<string | null>(null);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setValidationError(null);

    if (occurrenceLimit < 1 || occurrenceLimit > 1000) {
      setValidationError('Occurrence limit must be between 1 and 1000.');
      return;
    }

    if (delayMs < 0 || delayMs > 60000) {
      setValidationError('Delay must be between 0 and 60000 ms.');
      return;
    }

    const trimmedHash = packetHash.trim();
    if (trimmedHash && !/^[a-fA-F0-9]{64}$/.test(trimmedHash)) {
      setValidationError('Packet hash must be a valid 64-character hexadecimal SHA-256 string.');
      return;
    }

    try {
      await onInject({
        faultType,
        sourceNode: sourceNode.trim() || '*',
        destinationNode: destinationNode.trim() || '*',
        occurrenceLimit,
        delayMs,
        packetHash: trimmedHash || undefined
      });
      onClose();
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : String(err);
      setValidationError(msg || 'Failed to register fault rule.');
    }
  };

  const deviceOptions = [
    { value: '*', label: 'Any Device (*)' },
    { value: 'phone-alice', label: 'phone-alice' },
    { value: 'phone-stranger1', label: 'phone-stranger1' },
    { value: 'phone-stranger2', label: 'phone-stranger2' },
    { value: 'phone-stranger3', label: 'phone-stranger3' },
    { value: 'phone-bridge', label: 'phone-bridge (4G)' }
  ];

  return (
    <Modal isOpen={isOpen} onClose={onClose} title="Arm Custom Simulation Fault Rule">
      <form onSubmit={handleSubmit} className="space-y-4">
        <p className="text-xs text-slate-400">
          Registers a deterministic fault condition in the <code>FaultInjector</code> engine.
          Operates strictly at the virtual mesh and transport layer, never mutating financial balances directly.
        </p>

        {validationError && (
          <div className="bg-rose-950/70 border border-rose-800 text-rose-300 px-3 py-2 rounded-lg text-xs">
            {validationError}
          </div>
        )}

        <div>
          <label className="block text-xs font-semibold text-slate-300 uppercase mb-1">Fault Type</label>
          <select
            value={faultType}
            onChange={(e) => setFaultType(e.target.value)}
            className="w-full bg-[#0d1117] border border-[#30363d] rounded-lg px-3 py-2 text-xs text-slate-200"
          >
            {FAULT_TYPES.map((ft) => (
              <option key={ft.type} value={ft.type}>
                {ft.type} — {ft.description}
              </option>
            ))}
          </select>
        </div>

        <div className="grid grid-cols-2 gap-3">
          <div>
            <label className="block text-xs font-semibold text-slate-300 uppercase mb-1">Source Node</label>
            <select
              value={sourceNode}
              onChange={(e) => setSourceNode(e.target.value)}
              className="w-full bg-[#0d1117] border border-[#30363d] rounded-lg px-3 py-2 text-xs text-slate-200"
            >
              {deviceOptions.map((opt) => (
                <option key={opt.value} value={opt.value}>
                  {opt.label}
                </option>
              ))}
            </select>
          </div>

          <div>
            <label className="block text-xs font-semibold text-slate-300 uppercase mb-1">Destination Node</label>
            <select
              value={destinationNode}
              onChange={(e) => setDestinationNode(e.target.value)}
              className="w-full bg-[#0d1117] border border-[#30363d] rounded-lg px-3 py-2 text-xs text-slate-200"
            >
              {deviceOptions.map((opt) => (
                <option key={opt.value} value={opt.value}>
                  {opt.label}
                </option>
              ))}
            </select>
          </div>
        </div>

        <div className="grid grid-cols-2 gap-3">
          <div>
            <label className="block text-xs font-semibold text-slate-300 uppercase mb-1">
              Occurrence Limit (1–1000)
            </label>
            <input
              type="number"
              min={1}
              max={1000}
              value={occurrenceLimit}
              onChange={(e) => setOccurrenceLimit(parseInt(e.target.value, 10) || 1)}
              className="w-full bg-[#0d1117] border border-[#30363d] rounded-lg px-3 py-2 text-xs text-slate-200"
              required
            />
          </div>

          <div>
            <label className="block text-xs font-semibold text-slate-300 uppercase mb-1">
              Step Delay (0–60000 ms)
            </label>
            <input
              type="number"
              min={0}
              max={60000}
              value={delayMs}
              onChange={(e) => setDelayMs(parseInt(e.target.value, 10) || 0)}
              className="w-full bg-[#0d1117] border border-[#30363d] rounded-lg px-3 py-2 text-xs text-slate-200"
            />
          </div>
        </div>

        <div>
          <label className="block text-xs font-semibold text-slate-300 uppercase mb-1">
            Target Packet Hash (Optional 64-char Hex)
          </label>
          <input
            type="text"
            placeholder="Leave blank to match any packet (*)"
            value={packetHash}
            onChange={(e) => setPacketHash(e.target.value)}
            className="w-full bg-[#0d1117] border border-[#30363d] rounded-lg px-3 py-2 text-xs font-mono text-slate-200"
          />
        </div>

        <div className="pt-3 flex justify-end space-x-3">
          <Button variant="outline" type="button" onClick={onClose}>
            Cancel
          </Button>
          <Button variant="primary" type="submit" loading={loading}>
            Arm Rule in Engine
          </Button>
        </div>
      </form>
    </Modal>
  );
};
