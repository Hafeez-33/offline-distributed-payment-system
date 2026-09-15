import React from 'react';

interface BadgeProps {
  variant?: 'healthy' | 'degraded' | 'disputed' | 'failed' | 'neutral' | 'blue';
  children: React.ReactNode;
  className?: string;
}

export const Badge: React.FC<BadgeProps> = ({
  variant = 'neutral',
  children,
  className = ''
}) => {
  const variantStyles = {
    healthy: 'bg-emerald-950 text-emerald-400 border-emerald-700/50',
    degraded: 'bg-amber-950 text-amber-400 border-amber-700/50',
    disputed: 'bg-rose-950 text-rose-400 border-rose-700/50',
    failed: 'bg-red-950 text-red-400 border-red-700/50 animate-pulse',
    neutral: 'bg-slate-900 text-slate-400 border-slate-700/50',
    blue: 'bg-sky-950 text-sky-400 border-sky-700/50'
  };

  return (
    <span
      className={`inline-flex items-center px-2 py-0.5 rounded-full text-xs font-semibold border ${variantStyles[variant]} ${className}`}
    >
      {children}
    </span>
  );
};
