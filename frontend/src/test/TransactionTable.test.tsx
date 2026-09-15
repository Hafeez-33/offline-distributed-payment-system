import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { TransactionTable } from '../components/transactions/TransactionTable';
import { TransactionView } from '../types';

describe('TransactionTable Component', () => {
  const mockTxs: TransactionView[] = [
    {
      id: 42,
      packetHash: 'd29be38db8e04117b3543d463d41076f8753232145e1d51a2d4806a6b5a3e110',
      senderVpa: 'alice@demo',
      receiverVpa: 'bob@demo',
      amount: '45.00',
      signedAt: '2026-09-14T12:00:00Z',
      settledAt: '2026-09-14T12:01:00Z',
      bridgeNodeId: 'phone-bridge',
      hopCount: 2,
      status: 'SETTLED',
      walletId: 'WLT-1234',
      sequenceCounter: 2,
      conflictReason: null,
      winningTransactionId: null,
      receiptSignature: 'mock-sig'
    },
    {
      id: 43,
      packetHash: 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855',
      senderVpa: 'alice@demo',
      receiverVpa: 'carol@demo',
      amount: '100.00',
      signedAt: '2026-09-14T12:05:00Z',
      settledAt: null,
      bridgeNodeId: 'phone-bridge',
      hopCount: 1,
      status: 'CONFLICTING',
      walletId: 'WLT-1234',
      sequenceCounter: 2,
      conflictReason: 'Counter 2 previously settled by tx 42',
      winningTransactionId: 42,
      receiptSignature: null
    }
  ];

  it('renders transactions with formatted rupee currency and status badges', () => {
    render(<TransactionTable transactions={mockTxs} onSelectTx={vi.fn()} />);

    expect(screen.getByText('#42')).toBeInTheDocument();
    expect(screen.getByText('₹ 45.00')).toBeInTheDocument();
    expect(screen.getByText('SETTLED')).toBeInTheDocument();

    expect(screen.getByText('#43')).toBeInTheDocument();
    expect(screen.getByText('₹ 100.00')).toBeInTheDocument();
    expect(screen.getByText('CONFLICTING')).toBeInTheDocument();
  });

  it('triggers onSelectTx callback with the clicked transaction', () => {
    const handleSelect = vi.fn();
    render(<TransactionTable transactions={mockTxs} onSelectTx={handleSelect} />);

    fireEvent.click(screen.getByText('#42'));
    expect(handleSelect).toHaveBeenCalledWith(mockTxs[0]);
  });

  it('renders empty message when no transactions match', () => {
    render(<TransactionTable transactions={[]} onSelectTx={vi.fn()} />);
    expect(screen.getByText(/no transactions match the selected filter/i)).toBeInTheDocument();
  });
});
