import React from 'react';
import { FaultRuleView } from '../../types';
import { Badge } from '../common/Badge';
import { Button } from '../common/Button';
import { Trash2 } from 'lucide-react';

interface FaultRuleTableProps {
  rules: FaultRuleView[];
  onDeleteRule: (faultId: string) => void;
  deletingId: string | null;
  disabled?: boolean;
}

export const FaultRuleTable: React.FC<FaultRuleTableProps> = ({
  rules,
  onDeleteRule,
  deletingId,
  disabled = false
}) => {
  if (rules.length === 0) {
    return (
      <div className="text-center py-10 text-xs text-slate-400 border border-dashed border-[#30363d] rounded-xl">
        No active fault rules in engine. Engine operating in transparent passthrough mode.
      </div>
    );
  }

  return (
    <div className="overflow-x-auto">
      <table className="w-full text-left text-xs text-slate-300">
        <thead className="bg-[#0d1117] text-[11px] text-slate-400 uppercase tracking-wider border-b border-[#30363d]">
          <tr>
            <th className="px-4 py-3">Rule ID</th>
            <th className="px-4 py-3">Fault Type</th>
            <th className="px-4 py-3">Source → Dest</th>
            <th className="px-4 py-3">Target Packet Hash</th>
            <th className="px-4 py-3">Fired / Limit</th>
            <th className="px-4 py-3">Status</th>
            <th className="px-4 py-3 text-right">Remove</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-[#30363d]">
          {rules.map((rule) => {
            const isExhausted = rule.status === 'EXHAUSTED';

            return (
              <tr key={rule.faultId} className="hover:bg-slate-800/30 transition-colors">
                <td className="px-4 py-3 font-mono text-slate-200 font-semibold">
                  {rule.faultId}
                </td>
                <td className="px-4 py-3 font-mono font-medium text-amber-400">
                  {rule.faultType}
                </td>
                <td className="px-4 py-3 font-mono text-slate-300">
                  {rule.sourceNode} → {rule.destinationNode}
                </td>
                <td className="px-4 py-3 font-mono text-slate-400">
                  {rule.packetHash ? `${rule.packetHash.substring(0, 12)}...` : 'Any (*)'}
                </td>
                <td className="px-4 py-3 font-mono font-bold">
                  {rule.activations} / {rule.occurrenceLimit}
                </td>
                <td className="px-4 py-3">
                  <Badge variant={isExhausted ? 'neutral' : 'degraded'}>
                    {rule.status}
                  </Badge>
                </td>
                <td className="px-4 py-3 text-right">
                  <Button
                    variant="danger"
                    onClick={() => onDeleteRule(rule.faultId)}
                    loading={deletingId === rule.faultId}
                    disabled={disabled}
                    className="text-xs py-1 px-2.5"
                    title="Remove rule without resetting other rules or metrics"
                  >
                    <Trash2 className="w-3.5 h-3.5 mr-1" />
                    Remove
                  </Button>
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
};
