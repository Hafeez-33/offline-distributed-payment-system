import React from 'react';
import {
  LayoutDashboard,
  Network,
  Wallet,
  ReceiptText,
  ShieldCheck,
  Flame
} from 'lucide-react';

export type TabKey = 'overview' | 'mesh' | 'wallets' | 'transactions' | 'reliability' | 'faults';

interface SidebarProps {
  currentTab: TabKey;
  onSelectTab: (tab: TabKey) => void;
  disputedCount?: number;
  activeFaultsCount?: number;
}

export const Sidebar: React.FC<SidebarProps> = ({
  currentTab,
  onSelectTab,
  disputedCount = 0,
  activeFaultsCount = 0
}) => {
  const navItems = [
    { key: 'overview', label: 'Overview', icon: LayoutDashboard },
    { key: 'mesh', label: 'Mesh Topology', icon: Network },
    {
      key: 'wallets',
      label: 'Wallets & Escrow',
      icon: Wallet,
      badge: disputedCount > 0 ? `${disputedCount} disp` : undefined,
      badgeColor: 'bg-rose-950 text-rose-400 border-rose-800'
    },
    { key: 'transactions', label: 'Transactions', icon: ReceiptText },
    { key: 'reliability', label: 'Reliability & Invariants', icon: ShieldCheck },
    {
      key: 'faults',
      label: 'Fault Lab',
      icon: Flame,
      badge: activeFaultsCount > 0 ? `${activeFaultsCount} active` : undefined,
      badgeColor: 'bg-amber-950 text-amber-400 border-amber-800'
    }
  ];

  return (
    <aside className="w-64 border-r border-[#30363d] bg-[#161b22] flex flex-col justify-between shrink-0 min-h-[calc(100vh-4rem)]">
      <nav className="p-4 space-y-1.5">
        {navItems.map((item) => {
          const Icon = item.icon;
          const isActive = currentTab === item.key;

          return (
            <button
              key={item.key}
              onClick={() => onSelectTab(item.key as TabKey)}
              className={`w-full flex items-center justify-between px-3.5 py-2.5 rounded-lg text-sm font-medium transition-all ${
                isActive
                  ? 'bg-emerald-600/15 text-emerald-400 border border-emerald-600/30'
                  : 'text-slate-400 hover:text-slate-200 hover:bg-slate-800/60'
              }`}
            >
              <div className="flex items-center space-x-3">
                <Icon className={`w-4 h-4 ${isActive ? 'text-emerald-400' : 'text-slate-400'}`} />
                <span>{item.label}</span>
              </div>
              {item.badge && (
                <span className={`text-[10px] px-2 py-0.5 rounded-full border font-semibold ${item.badgeColor}`}>
                  {item.badge}
                </span>
              )}
            </button>
          );
        })}
      </nav>

      <div className="p-4 border-t border-[#30363d] text-xs text-slate-400 space-y-1">
        <p className="font-semibold text-slate-400">Offline Distributed UPI</p>
        <p>Spring Boot + React Live Monitor</p>
        <p className="text-[11px] text-slate-400">Phase 6 Simulation Dashboard</p>
      </div>
    </aside>
  );
};
