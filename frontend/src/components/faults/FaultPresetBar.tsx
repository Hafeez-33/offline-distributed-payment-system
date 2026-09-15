import React from 'react';
import { WifiOff, Database, RotateCw, ShieldAlert, Copy } from 'lucide-react';
import { FaultRuleRequest } from '../../types';

interface FaultPresetBarProps {
  onApplyPreset: (rule: FaultRuleRequest) => Promise<void>;
  loading: boolean;
  disabled?: boolean;
}

export const FaultPresetBar: React.FC<FaultPresetBarProps> = ({
  onApplyPreset,
  loading,
  disabled = false
}) => {
  const presets: {
    label: string;
    icon: React.ReactNode;
    rule: FaultRuleRequest;
  }[] = [
    {
      label: '4G Bridge Outage',
      icon: <WifiOff className="w-4 h-4 text-amber-400" />,
      rule: {
        faultType: 'BRIDGE_UNAVAILABLE',
        sourceNode: 'phone-bridge',
        destinationNode: '*',
        occurrenceLimit: 2
      }
    },
    {
      label: 'DB Lock Contention',
      icon: <Database className="w-4 h-4 text-rose-400" />,
      rule: {
        faultType: 'TRANSIENT_DATABASE_FAILURE',
        sourceNode: '*',
        destinationNode: '*',
        occurrenceLimit: 2
      }
    },
    {
      label: 'Lost Post-Commit HTTP Response',
      icon: <RotateCw className="w-4 h-4 text-sky-400" />,
      rule: {
        faultType: 'STALE_RESPONSE',
        sourceNode: '*',
        destinationNode: '*',
        occurrenceLimit: 1
      }
    },
    {
      label: 'Corrupted Ciphertext',
      icon: <ShieldAlert className="w-4 h-4 text-purple-400" />,
      rule: {
        faultType: 'CORRUPTED_PACKET_PAYLOAD',
        sourceNode: '*',
        destinationNode: '*',
        occurrenceLimit: 1
      }
    },
    {
      label: 'Duplicate Storm (10x)',
      icon: <Copy className="w-4 h-4 text-blue-400" />,
      rule: {
        faultType: 'DUPLICATE',
        sourceNode: '*',
        destinationNode: '*',
        occurrenceLimit: 10
      }
    }
  ];

  return (
    <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-5 gap-2.5">
      {presets.map((p) => (
        <button
          key={p.label}
          onClick={() => onApplyPreset(p.rule)}
          disabled={disabled || loading}
          className="p-3 bg-[#0d1117] border border-[#30363d] hover:border-slate-600 rounded-xl text-left transition-all disabled:opacity-50 flex items-start gap-2.5 select-none"
        >
          <div className="p-1.5 bg-[#161b22] rounded-lg border border-[#30363d]">{p.icon}</div>
          <div>
            <div className="text-xs font-semibold text-slate-200">{p.label}</div>
            <div className="text-[10px] text-slate-400 mt-0.5">{p.rule.faultType}</div>
          </div>
        </button>
      ))}
    </div>
  );
};
