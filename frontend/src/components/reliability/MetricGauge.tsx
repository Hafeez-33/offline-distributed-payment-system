import React from 'react';

interface MetricGaugeProps {
  label: string;
  count: number;
  icon?: React.ReactNode;
  color?: 'emerald' | 'amber' | 'rose' | 'blue' | 'slate';
  description?: string;
}

export const MetricGauge: React.FC<MetricGaugeProps> = ({
  label,
  count,
  icon,
  color = 'slate',
  description
}) => {
  const colorStyles = {
    emerald: 'text-emerald-400 bg-emerald-950/40 border-emerald-800/40',
    amber: 'text-amber-400 bg-amber-950/40 border-amber-800/40',
    rose: 'text-rose-400 bg-rose-950/40 border-rose-800/40',
    blue: 'text-sky-400 bg-sky-950/40 border-sky-800/40',
    slate: 'text-slate-300 bg-slate-900 border-slate-700/50'
  };

  return (
    <div className={`p-4 rounded-xl border ${colorStyles[color]} flex flex-col justify-between`}>
      <div className="flex items-center justify-between">
        <span className="text-xs font-semibold text-slate-300 uppercase tracking-wider">{label}</span>
        {icon}
      </div>
      <div className="mt-3">
        <span className="text-2xl font-bold font-mono tracking-tight">{count}</span>
        {description && <p className="text-[11px] text-slate-400 mt-1">{description}</p>}
      </div>
    </div>
  );
};
