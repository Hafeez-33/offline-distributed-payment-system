import React from 'react';
import { Loader2 } from 'lucide-react';

interface ButtonProps extends React.ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: 'primary' | 'secondary' | 'danger' | 'outline';
  loading?: boolean;
  disabledReason?: string;
}

export const Button: React.FC<ButtonProps> = ({
  variant = 'primary',
  loading = false,
  disabled = false,
  disabledReason,
  children,
  className = '',
  ...props
}) => {
  const baseStyles = 'inline-flex items-center justify-center font-medium rounded-lg text-sm px-4 py-2 transition-all duration-150 select-none';

  const variantStyles = {
    primary: 'bg-emerald-600 hover:bg-emerald-500 active:bg-emerald-700 text-white shadow-sm disabled:bg-emerald-950 disabled:text-emerald-700',
    secondary: 'bg-blue-600 hover:bg-blue-500 active:bg-blue-700 text-white shadow-sm disabled:bg-blue-950 disabled:text-blue-700',
    danger: 'bg-rose-600 hover:bg-rose-500 active:bg-rose-700 text-white shadow-sm disabled:bg-rose-950 disabled:text-rose-700',
    outline: 'border border-slate-700 hover:bg-slate-800 text-slate-200 active:bg-slate-900 disabled:border-slate-800 disabled:text-slate-600'
  };

  return (
    <button
      className={`${baseStyles} ${variantStyles[variant]} ${disabled || loading ? 'cursor-not-allowed opacity-60' : ''} ${className}`}
      disabled={disabled || loading}
      title={disabled ? disabledReason : undefined}
      {...props}
    >
      {loading && <Loader2 className="w-4 h-4 mr-2 animate-spin" />}
      {children}
    </button>
  );
};
