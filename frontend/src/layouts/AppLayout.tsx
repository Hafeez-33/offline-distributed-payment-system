import React from 'react';
import { Header } from './Header';
import { Sidebar, TabKey } from './Sidebar';
import { AlertCircle } from 'lucide-react';

interface AppLayoutProps {
  currentTab: TabKey;
  onSelectTab: (tab: TabKey) => void;
  isStale: boolean;
  isDisconnected: boolean;
  lastUpdated: Date | null;
  onRefresh: () => void;
  refreshing: boolean;
  disputedCount?: number;
  activeFaultsCount?: number;
  children: React.ReactNode;
}

export const AppLayout: React.FC<AppLayoutProps> = ({
  currentTab,
  onSelectTab,
  isStale,
  isDisconnected,
  lastUpdated,
  onRefresh,
  refreshing,
  disputedCount = 0,
  activeFaultsCount = 0,
  children
}) => {
  return (
    <div className="min-h-screen flex flex-col bg-[#0d1117] text-[#e6edf3]">
      <Header
        isStale={isStale}
        isDisconnected={isDisconnected}
        lastUpdated={lastUpdated}
        onRefresh={onRefresh}
        refreshing={refreshing}
      />

      {/* Disconnect Alert Banner */}
      {isDisconnected && (
        <div className="bg-rose-950/80 border-b border-rose-800 text-rose-300 px-6 py-2.5 text-xs flex items-center justify-between">
          <div className="flex items-center space-x-2">
            <AlertCircle className="w-4 h-4 text-rose-400 shrink-0" />
            <span>
              <strong>Backend connection lost.</strong> Cannot reach http://localhost:8080.
              Actions are temporarily disabled. Retrying in background...
            </span>
          </div>
          <button
            onClick={onRefresh}
            className="underline font-semibold hover:text-rose-100 cursor-pointer ml-4"
          >
            Retry Now
          </button>
        </div>
      )}

      {/* Stale State Banner */}
      {!isDisconnected && isStale && (
        <div className="bg-amber-950/80 border-b border-amber-800 text-amber-300 px-6 py-2 text-xs flex items-center space-x-2">
          <AlertCircle className="w-4 h-4 text-amber-400 shrink-0" />
          <span>
            <strong>Warning: Stale data.</strong> Network response delay exceeded 5 seconds.
            Mutation controls locked until live state is recovered.
          </span>
        </div>
      )}

      <div className="flex flex-1 overflow-hidden">
        <Sidebar
          currentTab={currentTab}
          onSelectTab={onSelectTab}
          disputedCount={disputedCount}
          activeFaultsCount={activeFaultsCount}
        />
        <main className="flex-1 overflow-y-auto p-6 lg:p-8">{children}</main>
      </div>
    </div>
  );
};
