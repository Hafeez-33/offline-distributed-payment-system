import React, { useState, useCallback } from 'react';
import { AppLayout } from './layouts/AppLayout';
import { TabKey } from './layouts/Sidebar';
import { OverviewPage } from './pages/OverviewPage';
import { MeshPage } from './pages/MeshPage';
import { WalletsPage } from './pages/WalletsPage';
import { TransactionsPage } from './pages/TransactionsPage';
import { ReliabilityPage } from './pages/ReliabilityPage';
import { FaultInjectionPage } from './pages/FaultInjectionPage';
import { usePolling } from './hooks/usePolling';
import { api } from './services/api';

export const App: React.FC = () => {
  const [currentTab, setCurrentTab] = useState<TabKey>('overview');
  const [manualRefreshing, setManualRefreshing] = useState(false);

  // Central Polling Hooks (adaptive 2s active / 10s hidden, 5s stale threshold)
  const overviewPoller = usePolling({
    fetcher: api.getOverview
  });

  const meshPoller = usePolling({
    fetcher: api.getMeshSummary
  });

  const walletPoller = usePolling({
    fetcher: api.getWallets
  });

  const isStale = overviewPoller.isStale || meshPoller.isStale || walletPoller.isStale;
  const isDisconnected = overviewPoller.isDisconnected && meshPoller.isDisconnected;
  const lastUpdated = overviewPoller.lastUpdated || meshPoller.lastUpdated;

  const handleGlobalRefresh = useCallback(async () => {
    try {
      setManualRefreshing(true);
      await Promise.all([
        overviewPoller.refresh(),
        meshPoller.refresh(),
        walletPoller.refresh()
      ]);
    } finally {
      setManualRefreshing(false);
    }
  }, [overviewPoller, meshPoller, walletPoller]);

  return (
    <AppLayout
      currentTab={currentTab}
      onSelectTab={setCurrentTab}
      isStale={isStale}
      isDisconnected={isDisconnected}
      lastUpdated={lastUpdated}
      onRefresh={handleGlobalRefresh}
      refreshing={manualRefreshing}
      disputedCount={overviewPoller.data?.disputedWallets}
      activeFaultsCount={overviewPoller.data?.activeFaultRules}
    >
      {currentTab === 'overview' && (
        <OverviewPage
          overview={overviewPoller.data}
          loading={overviewPoller.loading}
          isStale={isStale}
          isDisconnected={isDisconnected}
          onRefresh={handleGlobalRefresh}
        />
      )}

      {currentTab === 'mesh' && (
        <MeshPage
          meshState={meshPoller.data}
          loading={meshPoller.loading}
          isStale={isStale}
          isDisconnected={isDisconnected}
          onRefresh={handleGlobalRefresh}
        />
      )}

      {currentTab === 'wallets' && (
        <WalletsPage
          walletState={walletPoller.data}
          loading={walletPoller.loading}
          isStale={isStale}
          isDisconnected={isDisconnected}
          onRefresh={handleGlobalRefresh}
        />
      )}

      {currentTab === 'transactions' && <TransactionsPage />}

      {currentTab === 'reliability' && <ReliabilityPage />}

      {currentTab === 'faults' && (
        <FaultInjectionPage
          isStale={isStale}
          isDisconnected={isDisconnected}
          onRefreshParent={handleGlobalRefresh}
        />
      )}
    </AppLayout>
  );
};
