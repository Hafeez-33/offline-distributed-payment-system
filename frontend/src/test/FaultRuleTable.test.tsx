import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { FaultRuleTable } from '../components/faults/FaultRuleTable';
import { FaultRuleView } from '../types';

describe('FaultRuleTable Component', () => {
  const mockRules: FaultRuleView[] = [
    {
      faultId: 'rule-test-1',
      faultType: 'TRANSIENT_DATABASE_FAILURE',
      sourceNode: '*',
      destinationNode: '*',
      occurrenceLimit: 3,
      activations: 1,
      packetHash: null,
      status: 'ACTIVE'
    },
    {
      faultId: 'rule-test-2',
      faultType: 'BRIDGE_UNAVAILABLE',
      sourceNode: 'phone-bridge',
      destinationNode: '*',
      occurrenceLimit: 2,
      activations: 2,
      packetHash: 'abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789',
      status: 'EXHAUSTED'
    }
  ];

  it('renders all fault rules with correct type and status', () => {
    render(
      <FaultRuleTable
        rules={mockRules}
        onDeleteRule={vi.fn()}
        deletingId={null}
      />
    );

    expect(screen.getByText('rule-test-1')).toBeInTheDocument();
    expect(screen.getByText('TRANSIENT_DATABASE_FAILURE')).toBeInTheDocument();
    expect(screen.getByText('1 / 3')).toBeInTheDocument();
    expect(screen.getByText('ACTIVE')).toBeInTheDocument();

    expect(screen.getByText('rule-test-2')).toBeInTheDocument();
    expect(screen.getByText('BRIDGE_UNAVAILABLE')).toBeInTheDocument();
    expect(screen.getByText('2 / 2')).toBeInTheDocument();
    expect(screen.getByText('EXHAUSTED')).toBeInTheDocument();
  });

  it('calls onDeleteRule when clicking Remove button', () => {
    const handleDelete = vi.fn();
    render(
      <FaultRuleTable
        rules={mockRules}
        onDeleteRule={handleDelete}
        deletingId={null}
      />
    );

    const removeButtons = screen.getAllByRole('button', { name: /remove/i });
    fireEvent.click(removeButtons[0]);

    expect(handleDelete).toHaveBeenCalledWith('rule-test-1');
  });

  it('shows empty state when no rules exist', () => {
    render(
      <FaultRuleTable
        rules={[]}
        onDeleteRule={vi.fn()}
        deletingId={null}
      />
    );

    expect(screen.getByText(/no active fault rules in engine/i)).toBeInTheDocument();
  });
});
