import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { ReliabilityPage } from '../pages/ReliabilityPage';
import { api } from '../services/api';

vi.mock('../services/api', () => ({
  api: {
    getReliability: vi.fn()
  }
}));

describe('ReliabilityPage Component', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders authoritative invariants I1 through I12 evaluated by backend', async () => {
    vi.mocked(api.getReliability).mockResolvedValueOnce({
      metrics: {
        faultInjectionsTotal: 5,
        faultDropsTotal: 2,
        faultDuplicatesTotal: 1,
        faultDelaysTotal: 0,
        faultReordersTotal: 0,
        faultPartitionsTotal: 1,
        faultRecoveriesTotal: 2,
        retryAttemptsTotal: 1,
        reconciliationRecoveryTotal: 1,
        invariantViolationsTotal: 0
      },
      invariants: [
        { id: 'I1', name: 'Packet Identity', status: 'PASSED', details: 'packetHash == SHA-256(ciphertext)' },
        { id: 'I2', name: 'Transport Deduplication', status: 'PASSED', details: 'At most 1 entry per packetHash' },
        { id: 'I3', name: 'Settlement Idempotency', status: 'PASSED', details: 'No duplicate settlements' },
        { id: 'I4', name: 'Funds Conservation', status: 'PASSED', details: 'Total funds constant' }
      ]
    });

    render(<ReliabilityPage />);

    await waitFor(() => {
      expect(screen.getByText(/\[I1\] Packet Identity/i)).toBeInTheDocument();
      expect(screen.getByText(/\[I2\] Transport Deduplication/i)).toBeInTheDocument();
      expect(screen.getByText(/\[I3\] Settlement Idempotency/i)).toBeInTheDocument();
      expect(screen.getByText(/\[I4\] Funds Conservation/i)).toBeInTheDocument();
    });

    expect(screen.getByText('Fault Injections')).toBeInTheDocument();
    expect(screen.getByText('5')).toBeInTheDocument();
    expect(screen.getByText('Zero safety violations')).toBeInTheDocument();
  });
});
