import React, { useState } from 'react';
import { Network, Scissors, HeartHandshake, Radio, Share2, AlertTriangle, ShieldCheck } from 'lucide-react';
import { MeshTopologyState } from '../types';
import { TopologyCanvas } from '../components/mesh/TopologyCanvas';
import { NodeDetailsDrawer } from '../components/mesh/NodeDetailsDrawer';
import { LinkDescriptor } from '../utils/topologyLayout';
import { Card } from '../components/common/Card';
import { Button } from '../components/common/Button';
import { Badge } from '../components/common/Badge';
import { api } from '../services/api';

interface MeshPageProps {
  meshState: MeshTopologyState | null;
  loading: boolean;
  isStale: boolean;
  isDisconnected: boolean;
  onRefresh: () => Promise<void>;
}

export const MeshPage: React.FC<MeshPageProps> = ({
  meshState,
  loading,
  isStale,
  isDisconnected,
  onRefresh
}) => {
  const [selectedDeviceId, setSelectedDeviceId] = useState<string | null>(null);
  const [actionLoading, setActionLoading] = useState<string | null>(null);
  const [actionNotice, setActionNotice] = useState<string | null>(null);

  const disabled = isStale || isDisconnected;

  const handleLinkClick = async (link: LinkDescriptor) => {
    try {
      setActionLoading(link.key);
      if (link.isSevered) {
        await api.heal({ nodeA: link.sourceId, nodeB: link.targetId });
        setActionNotice(`Healed link between ${link.sourceId} and ${link.targetId}`);
      } else {
        await api.partition({ nodeA: link.sourceId, nodeB: link.targetId });
        setActionNotice(`Severed link between ${link.sourceId} and ${link.targetId}`);
      }
      await onRefresh();
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : String(err);
      setActionNotice(`Action failed: ${msg}`);
    } finally {
      setActionLoading(null);
    }
  };

  const handleHealAll = async () => {
    try {
      setActionLoading('heal-all');
      await api.heal();
      setActionNotice('All severed mesh communication links healed.');
      await onRefresh();
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : String(err);
      setActionNotice(`Heal failed: ${msg}`);
    } finally {
      setActionLoading(null);
    }
  };

  const handleHealAndSync = async () => {
    try {
      setActionLoading('heal-sync');
      await api.heal();
      const res = await api.triggerAntiEntropy();
      setActionNotice(`Healed & Converged! Anti-entropy complete: ${res.totalTransfers} transfers, converged=${res.allReachableConverged}`);
      await onRefresh();
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : String(err);
      setActionNotice(`Operation failed: ${msg}`);
    } finally {
      setActionLoading(null);
    }
  };

  const handleSubmeshPartition = async () => {
    try {
      setActionLoading('submesh');
      // Partition phone-alice & phone-stranger1 from others
      await api.partition({
        submeshA: ['phone-alice', 'phone-stranger1'],
        submeshB: ['phone-stranger2', 'phone-stranger3', 'phone-bridge']
      });
      setActionNotice('Network partitioned into 2 submeshes: [alice, stranger1] vs [stranger2, stranger3, bridge]');
      await onRefresh();
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : String(err);
      setActionNotice(`Partition failed: ${msg}`);
    } finally {
      setActionLoading(null);
    }
  };

  const handleGossip = async () => {
    try {
      setActionLoading('gossip');
      const r = await api.triggerGossip();
      setActionNotice(`Push gossip complete: ${r.transfers} packet transfers.`);
      await onRefresh();
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : String(err);
      setActionNotice(`Gossip failed: ${msg}`);
    } finally {
      setActionLoading(null);
    }
  };

  const handleAntiEntropy = async () => {
    try {
      setActionLoading('sync');
      const r = await api.triggerAntiEntropy();
      setActionNotice(`Anti-entropy pull complete: ${r.totalTransfers} transfers, inSync=${r.sessionsInSync}, repaired=${r.sessionsRepaired}`);
      await onRefresh();
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : String(err);
      setActionNotice(`Sync failed: ${msg}`);
    } finally {
      setActionLoading(null);
    }
  };

  if (loading && !meshState) {
    return (
      <div className="space-y-6 animate-pulse">
        <div className="h-16 bg-slate-800/40 rounded-xl" />
        <div className="h-[420px] bg-slate-800/40 rounded-xl" />
      </div>
    );
  }

  return (
    <div className="space-y-6">
      {/* Topology Header & Controls Bar */}
      <div className="bg-[#161b22] border border-[#30363d] rounded-xl p-4 flex flex-col md:flex-row items-start md:items-center justify-between gap-4">
        <div>
          <h2 className="text-lg font-bold text-slate-100 flex items-center gap-2">
            <Network className="w-5 h-5 text-emerald-400" />
            <span>Interactive Mesh Topology</span>
          </h2>
          <p className="text-xs text-slate-400 mt-1">
            Click any link to sever or heal it. Click any device node to inspect authoritative digest,
            16 bucket checksums, and buffered packets.
          </p>
        </div>

        <div className="flex flex-wrap items-center gap-2">
          {meshState?.allConverged ? (
            <Badge variant="healthy" className="flex items-center gap-1">
              <ShieldCheck className="w-3.5 h-3.5" />
              <span>All Reachable In-Sync</span>
            </Badge>
          ) : (
            <Badge variant="degraded" className="flex items-center gap-1">
              <AlertTriangle className="w-3.5 h-3.5" />
              <span>Divergent Digests</span>
            </Badge>
          )}

          <Button
            variant="outline"
            onClick={handleGossip}
            loading={actionLoading === 'gossip'}
            disabled={disabled}
            className="text-xs py-1.5"
          >
            <Radio className="w-3.5 h-3.5 mr-1.5" />
            Push Gossip
          </Button>

          <Button
            variant="secondary"
            onClick={handleAntiEntropy}
            loading={actionLoading === 'sync'}
            disabled={disabled}
            className="text-xs py-1.5"
          >
            <Share2 className="w-3.5 h-3.5 mr-1.5" />
            Anti-Entropy Pull
          </Button>
        </div>
      </div>

      {actionNotice && (
        <div className="bg-slate-900 border border-slate-700 text-slate-200 text-xs px-4 py-2.5 rounded-lg flex items-center justify-between">
          <span>{actionNotice}</span>
          <button onClick={() => setActionNotice(null)} className="text-slate-400 hover:text-slate-200 ml-4">
            ✕
          </button>
        </div>
      )}

      {/* SVG Topology Graph */}
      <TopologyCanvas
        devices={meshState?.devices ?? []}
        severedLinks={meshState?.severedLinks ?? []}
        selectedDeviceId={selectedDeviceId}
        onNodeClick={(id) => setSelectedDeviceId(id)}
        onLinkClick={handleLinkClick}
        disabled={disabled}
      />

      {/* Partition & Healing Tools Card */}
      <Card
        title="✂️ Partition & Healing Controls"
        subtitle="Simulate physical Bluetooth signal blockages, building walls, or underground tunnels"
      >
        <div className="grid grid-cols-1 sm:grid-cols-3 gap-3">
          <Button
            variant="outline"
            onClick={handleSubmeshPartition}
            loading={actionLoading === 'submesh'}
            disabled={disabled}
            className="flex items-center justify-center gap-2 text-xs"
          >
            <Scissors className="w-4 h-4 text-rose-400" />
            <span>Partition Submesh [Alice, S1]</span>
          </Button>

          <Button
            variant="outline"
            onClick={handleHealAll}
            loading={actionLoading === 'heal-all'}
            disabled={disabled}
            className="flex items-center justify-center gap-2 text-xs"
          >
            <HeartHandshake className="w-4 h-4 text-emerald-400" />
            <span>Heal All Severed Links</span>
          </Button>

          <Button
            variant="primary"
            onClick={handleHealAndSync}
            loading={actionLoading === 'heal-sync'}
            disabled={disabled}
            className="flex items-center justify-center gap-2 text-xs"
          >
            <Share2 className="w-4 h-4" />
            <span>Heal & Anti-Entropy Converge</span>
          </Button>
        </div>
      </Card>

      {/* Slide-Over Node Details Drawer */}
      <NodeDetailsDrawer
        deviceId={selectedDeviceId}
        onClose={() => setSelectedDeviceId(null)}
      />
    </div>
  );
};
