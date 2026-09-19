import React from 'react';
import { RefreshCw, Wifi, WifiOff, AlertTriangle } from 'lucide-react';
import { Badge } from '../components/common/Badge';

interface HeaderProps {
  isStale: boolean;
  isDisconnected: boolean;
  lastUpdated: Date | null;
  onRefresh: () => void;
  refreshing: boolean;
}

export const Header: React.FC<HeaderProps> = ({
  isStale,
  isDisconnected,
  lastUpdated,
  onRefresh,
  refreshing
}) => {
  return (
    <header className="h-16 border-b border-[#30363d] bg-[#161b22]/70 backdrop-blur-md px-6 flex items-center justify-between sticky top-0 z-30">
      <div className="flex items-center space-x-3">
        <span className="font-bold text-lg text-slate-100 tracking-tight flex items-center">
          <span className="text-emerald-500 mr-2 text-xl">📡</span> UPI Offline Mesh
        </span>
        <span className="text-xs px-2 py-0.5 rounded-md bg-amber-950/60 border border-amber-800/50 text-amber-400 font-medium">
          SIMULATOR ONLY
        </span>
      </div>

      <div className="flex items-center space-x-4">
        {/* Connection status */}
        {isDisconnected ? (
          <Badge variant="failed" className="flex items-center gap-1.5 py-1 px-2.5">
            <WifiOff className="w-3.5 h-3.5" />
            <span>Backend Disconnected</span>
          </Badge>
        ) : isStale ? (
          <Badge variant="degraded" className="flex items-center gap-1.5 py-1 px-2.5">
            <AlertTriangle className="w-3.5 h-3.5" />
            <span>STALE DATA</span>
          </Badge>
        ) : (
          <Badge variant="healthy" className="flex items-center gap-1.5 py-1 px-2.5">
            <Wifi className="w-3.5 h-3.5" />
            <span>Live (2s Poll)</span>
          </Badge>
        )}

        {lastUpdated && (
          <span className="text-xs text-slate-400 hidden sm:inline-block">
            Updated: {lastUpdated.toLocaleTimeString()}
          </span>
        )}

        <button
          onClick={onRefresh}
          disabled={refreshing}
          className="p-2 text-slate-400 hover:text-slate-100 hover:bg-slate-800 rounded-lg transition-colors disabled:opacity-50"
          title="Manual refresh"
        >
          <RefreshCw className={`w-4 h-4 ${refreshing ? 'animate-spin' : ''}`} />
        </button>
      </div>
    </header>
  );
};
