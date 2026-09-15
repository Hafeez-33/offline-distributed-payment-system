import React, { useEffect, useState, useCallback } from 'react';
import { ShieldCheck, RefreshCw } from 'lucide-react';
import { ReliabilityReportState } from '../types';
import { InvariantCard } from '../components/reliability/InvariantCard';
import { MetricGauge } from '../components/reliability/MetricGauge';
import { Card } from '../components/common/Card';
import { api } from '../services/api';

export const ReliabilityPage: React.FC = () => {
  const [data, setData] = useState<ReliabilityReportState | null>(null);
  const [loading, setLoading] = useState<boolean>(true);

  const fetchReliability = useCallback(async () => {
    try {
      setLoading(true);
      const res = await api.getReliability();
      setData(res);
    } catch (err) {
      console.error('Failed to load reliability report:', err);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchReliability();
  }, [fetchReliability]);

  const metrics = data?.metrics;
  const invariants = data?.invariants ?? [];

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="bg-[#161b22] border border-[#30363d] rounded-xl p-5 flex flex-col md:flex-row items-start md:items-center justify-between gap-4">
        <div>
          <h2 className="text-lg font-bold text-slate-100 flex items-center gap-2">
            <ShieldCheck className="w-5 h-5 text-emerald-400" />
            <span>Reliability Metrics & Machine-Checkable Invariants</span>
          </h2>
          <p className="text-xs text-slate-400 mt-1">
            Authoritative server-evaluated distributed system safety proofs (Phase 5).
            Invariants I1–I12 are computed dynamically against backend state and repositories.
          </p>
        </div>

        <button
          onClick={fetchReliability}
          disabled={loading}
          className="flex items-center gap-1.5 text-xs text-slate-300 hover:text-slate-100 bg-[#0d1117] border border-[#30363d] px-3 py-2 rounded-lg"
        >
          <RefreshCw className={`w-3.5 h-3.5 ${loading ? 'animate-spin' : ''}`} />
          <span>Re-evaluate Audit</span>
        </button>
      </div>

      {/* Metric Counters Grid */}
      <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-5 gap-3">
        <MetricGauge
          label="Fault Injections"
          count={metrics?.faultInjectionsTotal ?? 0}
          color="blue"
          description="Total rules activated"
        />
        <MetricGauge
          label="Packet Drops"
          count={metrics?.faultDropsTotal ?? 0}
          color="amber"
          description="Packets dropped silently"
        />
        <MetricGauge
          label="Packet Duplicates"
          count={metrics?.faultDuplicatesTotal ?? 0}
          color="slate"
          description="Duplicate transmissions"
        />
        <MetricGauge
          label="Optimistic Retries"
          count={metrics?.retryAttemptsTotal ?? 0}
          color="emerald"
          description="Transient lock retries"
        />
        <MetricGauge
          label="Invariant Violations"
          count={metrics?.invariantViolationsTotal ?? 0}
          color={metrics?.invariantViolationsTotal === 0 ? 'emerald' : 'rose'}
          description={metrics?.invariantViolationsTotal === 0 ? 'Zero safety violations' : 'CRITICAL SAFETY FAILURE'}
        />
      </div>

      {/* Machine-Checkable Invariants Matrix */}
      <Card
        title="Authoritative Machine-Checkable Invariants (I1–I12)"
        subtitle="Evaluated exclusively by InvariantAuditService on the Spring Boot backend"
      >
        {loading && !data ? (
          <div className="py-12 text-center text-xs text-slate-400">
            Evaluating ledger consistency and cryptographic proofs...
          </div>
        ) : (
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-3">
            {invariants.map((inv) => (
              <InvariantCard key={inv.id} invariant={inv} />
            ))}
          </div>
        )}
      </Card>
    </div>
  );
};
