import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { AppLayout } from '../layouts/AppLayout';
import { OverviewPage } from '../pages/OverviewPage';

describe('Backend Outage and Stale State Safety', () => {
  it('displays persistent connection warning banner when disconnected', () => {
    render(
      <AppLayout
        currentTab="overview"
        onSelectTab={vi.fn()}
        isStale={false}
        isDisconnected={true}
        lastUpdated={null}
        onRefresh={vi.fn()}
        refreshing={false}
      >
        <div>Content</div>
      </AppLayout>
    );

    expect(screen.getByText(/backend connection lost/i)).toBeInTheDocument();
    expect(screen.getByText(/cannot reach http:\/\/localhost:8080/i)).toBeInTheDocument();
  });

  it('displays amber stale data banner when data exceeds stale threshold', () => {
    render(
      <AppLayout
        currentTab="overview"
        onSelectTab={vi.fn()}
        isStale={true}
        isDisconnected={false}
        lastUpdated={new Date()}
        onRefresh={vi.fn()}
        refreshing={false}
      >
        <div>Content</div>
      </AppLayout>
    );

    expect(screen.getByText(/warning: stale data/i)).toBeInTheDocument();
  });

  it('disables mutation buttons on OverviewPage when disconnected or stale', () => {
    render(
      <OverviewPage
        overview={{
          systemStatus: 'HEALTHY',
          totalDevices: 5,
          onlineBridges: 1,
          meshConverged: true,
          severedLinkCount: 0,
          totalHeldPackets: 0,
          totalAccounts: 4,
          totalLiquidBalance: '5000.00',
          totalEscrowBalance: '0.00',
          activeWallets: 0,
          disputedWallets: 0,
          settledTxCount: 0,
          conflictingTxCount: 0,
          pendingGapCount: 0,
          activeFaultRules: 0,
          invariantViolations: 0,
          timestamp: new Date().toISOString()
        }}
        loading={false}
        isStale={true}
        isDisconnected={false}
        onRefresh={vi.fn()}
      />
    );

    const composeBtn = screen.getByRole('button', { name: /compose payment/i });
    const gossipBtn = screen.getByRole('button', { name: /push gossip/i });
    const syncBtn = screen.getByRole('button', { name: /anti-entropy sync/i });
    const flushBtn = screen.getByRole('button', { name: /flush bridges/i });
    const resetBtn = screen.getByRole('button', { name: /reset mesh/i });

    expect(composeBtn).toBeDisabled();
    expect(gossipBtn).toBeDisabled();
    expect(syncBtn).toBeDisabled();
    expect(flushBtn).toBeDisabled();
    expect(resetBtn).toBeDisabled();
  });
});
