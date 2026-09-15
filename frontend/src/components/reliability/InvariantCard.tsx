import React from 'react';
import { InvariantCheckResult } from '../../types';
import { Badge } from '../common/Badge';
import { CheckCircle2, AlertTriangle, XCircle } from 'lucide-react';

interface InvariantCardProps {
  invariant: InvariantCheckResult;
}

export const InvariantCard: React.FC<InvariantCardProps> = ({ invariant }) => {
  const isPassed = invariant.status === 'PASSED';
  const isWarning = invariant.status === 'WARNING';

  const badgeVariant = isPassed ? 'healthy' : isWarning ? 'degraded' : 'failed';

  return (
    <div className="bg-[#0d1117] border border-[#30363d] rounded-xl p-4 flex flex-col justify-between space-y-2 hover:border-slate-700 transition-colors">
      <div className="flex items-center justify-between">
        <div className="flex items-center space-x-2">
          {isPassed ? (
            <CheckCircle2 className="w-4 h-4 text-emerald-400" />
          ) : isWarning ? (
            <AlertTriangle className="w-4 h-4 text-amber-400" />
          ) : (
            <XCircle className="w-4 h-4 text-rose-400 animate-pulse" />
          )}
          <span className="font-mono font-bold text-xs text-slate-100">
            [{invariant.id}] {invariant.name}
          </span>
        </div>
        <Badge variant={badgeVariant}>{invariant.status}</Badge>
      </div>
      <p className="text-xs text-slate-400 leading-relaxed">{invariant.details}</p>
    </div>
  );
};
