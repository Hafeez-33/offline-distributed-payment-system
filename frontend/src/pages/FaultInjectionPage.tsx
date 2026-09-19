import React, { useState, useEffect, useCallback } from 'react';
import { Flame, Plus, RotateCcw, AlertTriangle } from 'lucide-react';
import { FaultRuleView, FaultRuleRequest } from '../types';
import { FaultRuleTable } from '../components/faults/FaultRuleTable';
import { InjectFaultModal } from '../components/faults/InjectFaultModal';
import { FaultPresetBar } from '../components/faults/FaultPresetBar';
import { Card } from '../components/common/Card';
import { Button } from '../components/common/Button';
import { api } from '../services/api';

interface FaultInjectionPageProps {
  isStale: boolean;
  isDisconnected: boolean;
  onRefreshParent: () => Promise<void>;
}

export const FaultInjectionPage: React.FC<FaultInjectionPageProps> = ({
  isStale,
  isDisconnected,
  onRefreshParent
}) => {
  const [rules, setRules] = useState<FaultRuleView[]>([]);
  const [createModalOpen, setCreateModalOpen] = useState<boolean>(false);
  const [actionLoading, setActionLoading] = useState<string | null>(null);
  const [actionNotice, setActionNotice] = useState<{ text: string; type: 'success' | 'error' } | null>(null);

  const disabled = isStale || isDisconnected;

  const fetchRules = useCallback(async () => {
    try {
      const res = await api.listFaultRules();
      setRules(res);
    } catch (err: unknown) {
      console.error('Failed to load fault rules:', err);
    }
  }, []);

  useEffect(() => {
    fetchRules();
  }, [fetchRules]);

  const handleCreateRule = async (rule: FaultRuleRequest) => {
    try {
      setActionLoading('create');
      const res = await api.registerFaultRule(rule);
      setActionNotice({
        text: `Armed fault rule #${res.faultId} [${rule.faultType}]`,
        type: 'success'
      });
      await fetchRules();
      await onRefreshParent();
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : String(err);
      setActionNotice({ text: `Failed to arm rule: ${msg}`, type: 'error' });
      throw err;
    } finally {
      setActionLoading(null);
    }
  };

  const handleDeleteRule = async (faultId: string) => {
    try {
      setActionLoading(faultId);
      await api.deleteFaultRule(faultId);
      setActionNotice({
        text: `Removed fault rule #${faultId}. Remaining rules stay active.`,
        type: 'success'
      });
      await fetchRules();
      await onRefreshParent();
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : String(err);
      setActionNotice({ text: `Failed to remove rule: ${msg}`, type: 'error' });
    } finally {
      setActionLoading(null);
    }
  };

  const handleResetFaults = async () => {
    try {
      setActionLoading('reset');
      await api.resetFaults();
      setActionNotice({
        text: 'All fault rules cleared and reliability metrics reset to 0.',
        type: 'success'
      });
      await fetchRules();
      await onRefreshParent();
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : String(err);
      setActionNotice({ text: `Reset failed: ${msg}`, type: 'error' });
    } finally {
      setActionLoading(null);
    }
  };

  return (
    <div className="space-y-6">
      {/* SIMULATION ONLY Prominent Banner */}
      <div className="bg-amber-950/60 border border-amber-800/80 rounded-xl p-4 flex items-start gap-3 text-xs text-amber-200">
        <AlertTriangle className="w-5 h-5 text-amber-400 shrink-0 mt-0.5" />
        <div>
          <strong className="text-amber-300 font-bold uppercase tracking-wider text-[11px] block">
            ⚠️ SIMULATOR FAULT INJECTION LAB — BOUNDARY NOTICE
          </strong>
          <p className="mt-0.5 text-amber-200/90 leading-relaxed">
            Operates strictly at the virtual mesh, transport, and mock persistence layer (Phase 5).
            Fault rules are strictly sandboxed and <strong>never directly mutate</strong> ledger balances,
            account states, cryptographic nonces, or banking records.
          </p>
        </div>
      </div>

      {/* Header and Controls */}
      <div className="bg-[#161b22] border border-[#30363d] rounded-xl p-5 flex flex-col md:flex-row items-start md:items-center justify-between gap-4">
        <div>
          <h2 className="text-lg font-bold text-slate-100 flex items-center gap-2">
            <Flame className="w-5 h-5 text-amber-400" />
            <span>Deterministic Fault Injection Deck</span>
          </h2>
          <p className="text-xs text-slate-400 mt-1">
            Simulate hostile networks, 4G packet drops, duplicate bursts, and database contention.
          </p>
        </div>

        <div className="flex flex-wrap items-center gap-2.5">
          <Button
            variant="danger"
            onClick={handleResetFaults}
            loading={actionLoading === 'reset'}
            disabled={disabled}
            className="text-xs flex items-center gap-1.5"
          >
            <RotateCcw className="w-3.5 h-3.5" />
            <span>Reset All Faults</span>
          </Button>

          <Button
            variant="primary"
            onClick={() => setCreateModalOpen(true)}
            disabled={disabled}
            className="text-xs flex items-center gap-1.5"
          >
            <Plus className="w-4 h-4" />
            <span>Arm Custom Fault Rule</span>
          </Button>
        </div>
      </div>

      {actionNotice && (
        <div
          className={`p-3 rounded-lg text-xs font-medium border flex items-center justify-between ${
            actionNotice.type === 'success'
              ? 'bg-emerald-950 border-emerald-800 text-emerald-300'
              : 'bg-rose-950 border-rose-800 text-rose-300'
          }`}
        >
          <span>{actionNotice.text}</span>
          <button onClick={() => setActionNotice(null)} className="text-slate-400 hover:text-slate-200 ml-4">
            ✕
          </button>
        </div>
      )}

      {/* One-Click Scenario Presets */}
      <Card
        title="⚡ One-Click Reliability Presets"
        subtitle="Quickly trigger approved Phase 5 fault injection scenarios against the live simulator"
      >
        <FaultPresetBar
          onApplyPreset={handleCreateRule}
          loading={actionLoading === 'create'}
          disabled={disabled}
        />
      </Card>

      {/* Active Rules Table */}
      <Card
        title={`Active Rules in Fault Engine (${rules.length})`}
        subtitle="Each rule intercepts matching gossip, anti-entropy, bridge, or settlement operations"
      >
        <FaultRuleTable
          rules={rules}
          onDeleteRule={handleDeleteRule}
          deletingId={actionLoading}
          disabled={disabled}
        />
      </Card>

      {/* Inject Fault Modal */}
      <InjectFaultModal
        isOpen={createModalOpen}
        onClose={() => setCreateModalOpen(false)}
        onInject={handleCreateRule}
        loading={actionLoading === 'create'}
      />
    </div>
  );
};
