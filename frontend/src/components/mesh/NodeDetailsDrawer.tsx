import React, { useEffect, useState } from 'react';
import { X, Copy, Check, Shield, Layers, RefreshCw } from 'lucide-react';
import { DeviceDetailView } from '../../types';
import { api } from '../../services/api';
import { formatCurrency, formatTimestamp } from '../../utils/formatters';

interface NodeDetailsDrawerProps {
  deviceId: string | null;
  onClose: () => void;
}

export const NodeDetailsDrawer: React.FC<NodeDetailsDrawerProps> = ({
  deviceId,
  onClose
}) => {
  const [detail, setDetail] = useState<DeviceDetailView | null>(null);
  const [loading, setLoading] = useState<boolean>(false);
  const [copiedHash, setCopiedHash] = useState<string | null>(null);

  useEffect(() => {
    if (!deviceId) {
      setDetail(null);
      return;
    }

    let isMounted = true;
    setLoading(true);

    api.getDeviceDetail(deviceId)
      .then((data) => {
        if (isMounted) setDetail(data);
      })
      .catch((err) => {
        console.error('Failed to load device detail:', err);
      })
      .finally(() => {
        if (isMounted) setLoading(false);
      });

    return () => {
      isMounted = false;
    };
  }, [deviceId]);

  const copyToClipboard = (text: string) => {
    navigator.clipboard.writeText(text);
    setCopiedHash(text);
    setTimeout(() => setCopiedHash(null), 2000);
  };

  if (!deviceId) return null;

  return (
    <div className="fixed inset-y-0 right-0 z-40 w-full max-w-md bg-[#161b22] border-l border-[#30363d] shadow-2xl flex flex-col">
      {/* Drawer Header */}
      <div className="px-5 py-4 border-b border-[#30363d] flex items-center justify-between bg-[#0d1117]">
        <div className="flex items-center space-x-2">
          <div
            className={`w-3 h-3 rounded-full ${
              detail?.hasInternet ? 'bg-emerald-500' : 'bg-slate-500'
            }`}
          />
          <h3 className="text-base font-bold text-slate-100 font-mono">{deviceId}</h3>
          {detail?.hasInternet && (
            <span className="text-[10px] bg-emerald-950 text-emerald-400 border border-emerald-800 px-1.5 py-0.5 rounded font-bold">
              4G BRIDGE
            </span>
          )}
        </div>
        <button
          onClick={onClose}
          className="text-slate-400 hover:text-slate-200 p-1 rounded-lg hover:bg-slate-800 transition-colors"
        >
          <X className="w-5 h-5" />
        </button>
      </div>

      {/* Drawer Body */}
      <div className="flex-1 overflow-y-auto p-5 space-y-6">
        {loading && (
          <div className="flex items-center justify-center py-12 text-slate-400 space-x-2">
            <RefreshCw className="w-5 h-5 animate-spin" />
            <span className="text-sm">Fetching on-demand node diagnostics...</span>
          </div>
        )}

        {!loading && detail && (
          <>
            {/* Authoritative State Digest */}
            <div>
              <span className="text-xs font-semibold text-slate-400 uppercase tracking-wider block mb-1">
                State Digest (Anti-Entropy SHA-256)
              </span>
              <div className="flex items-center space-x-2 bg-[#0d1117] border border-[#30363d] rounded-lg p-2.5">
                <code className="text-xs font-mono text-emerald-400 break-all flex-1 select-all">
                  {detail.stateDigest}
                </code>
                <button
                  onClick={() => copyToClipboard(detail.stateDigest)}
                  className="text-slate-400 hover:text-slate-200 p-1"
                  title="Copy full digest"
                >
                  {copiedHash === detail.stateDigest ? (
                    <Check className="w-4 h-4 text-emerald-400" />
                  ) : (
                    <Copy className="w-4 h-4" />
                  )}
                </button>
              </div>
            </div>

            {/* Offline Wallet Monotonic State */}
            {detail.walletState && (
              <div className="border border-[#30363d] rounded-xl p-4 bg-[#0d1117]/60">
                <div className="flex items-center space-x-2 text-xs font-bold text-amber-400 uppercase tracking-wider mb-2">
                  <Shield className="w-4 h-4" />
                  <span>Device Hardware Monotonic Counter</span>
                </div>
                <div className="grid grid-cols-2 gap-3 text-xs">
                  <div>
                    <span className="text-slate-400">Wallet ID:</span>
                    <div className="font-mono text-slate-200 font-semibold">{detail.walletState.walletId}</div>
                  </div>
                  <div>
                    <span className="text-slate-400">Sequence Counter:</span>
                    <div className="font-mono text-emerald-400 font-bold text-sm">
                      #{detail.walletState.sequenceCounter}
                    </div>
                  </div>
                  <div>
                    <span className="text-slate-400">Wallet Epoch:</span>
                    <div className="font-mono text-slate-200">Epoch {detail.walletState.walletEpoch}</div>
                  </div>
                  <div>
                    <span className="text-slate-400">Cumulative Spend:</span>
                    <div className="font-mono text-slate-200">
                      {formatCurrency(detail.walletState.cumulativeSpend)}
                    </div>
                  </div>
                </div>
              </div>
            )}

            {/* Held Packets in Memory Store */}
            <div>
              <div className="flex items-center justify-between mb-2">
                <span className="text-xs font-semibold text-slate-400 uppercase tracking-wider flex items-center gap-1.5">
                  <Layers className="w-4 h-4 text-emerald-400" />
                  Held Packets ({detail.heldPackets.length})
                </span>
              </div>

              {detail.heldPackets.length === 0 ? (
                <div className="text-xs text-slate-400 py-3 text-center border border-dashed border-[#30363d] rounded-lg">
                  Zero packets held in volatile buffer
                </div>
              ) : (
                <div className="space-y-2">
                  {detail.heldPackets.map((pkt) => (
                    <div
                      key={pkt.packetId}
                      className="bg-[#0d1117] border border-[#30363d] rounded-lg p-2.5 text-xs space-y-1"
                    >
                      <div className="flex items-center justify-between">
                        <span className="font-mono font-semibold text-slate-200">
                          {pkt.packetId}
                        </span>
                        <span className="text-[10px] bg-slate-800 text-slate-300 px-1.5 py-0.5 rounded font-mono">
                          TTL: {pkt.ttl} hops
                        </span>
                      </div>
                      <div className="flex items-center justify-between text-[11px] text-slate-400">
                        <span className="font-mono select-all break-all">
                          {pkt.packetHash.substring(0, 16)}...{pkt.packetHash.substring(48)}
                        </span>
                        <button
                          onClick={() => copyToClipboard(pkt.packetHash)}
                          className="text-slate-400 hover:text-slate-200 p-0.5"
                        >
                          {copiedHash === pkt.packetHash ? (
                            <Check className="w-3.5 h-3.5 text-emerald-400" />
                          ) : (
                            <Copy className="w-3.5 h-3.5" />
                          )}
                        </button>
                      </div>
                      {pkt.createdAt && (
                        <div className="text-[10px] text-slate-400">
                          Created: {formatTimestamp(pkt.createdAt)}
                        </div>
                      )}
                    </div>
                  ))}
                </div>
              )}
            </div>

            {/* 16 Prefix Bucket Checksums */}
            <div>
              <span className="text-xs font-semibold text-slate-400 uppercase tracking-wider block mb-2">
                16 Prefix Bucket Checksums (Anti-Entropy Divergence)
              </span>
              <div className="grid grid-cols-4 gap-1.5 font-mono text-[10px]">
                {detail.bucketChecksums.map((chk, i) => (
                  <div
                    key={i}
                    className="bg-[#0d1117] border border-[#30363d] p-1.5 rounded text-center"
                  >
                    <div className="text-slate-400 text-[9px]">0x{i.toString(16).toUpperCase()}</div>
                    <div className="text-slate-200 truncate">{chk.substring(0, 8)}</div>
                  </div>
                ))}
              </div>
            </div>

            {/* Peer Synchronization Table */}
            <div>
              <span className="text-xs font-semibold text-slate-400 uppercase tracking-wider block mb-2">
                Peer Synchronization Status
              </span>
              <div className="space-y-1.5 text-xs">
                {Object.entries(detail.peerSyncSummary).map(([peer, rec]) => (
                  <div
                    key={peer}
                    className="flex items-center justify-between p-2 bg-[#0d1117] border border-[#30363d] rounded-lg"
                  >
                    <span className="font-mono text-slate-300">{peer}</span>
                    <span
                      className={`px-2 py-0.5 rounded text-[10px] font-semibold ${
                        rec.inSync
                          ? 'bg-emerald-950 text-emerald-400 border border-emerald-800/40'
                          : 'bg-amber-950 text-amber-400 border border-amber-800/40'
                      }`}
                    >
                      {rec.inSync ? '✓ IN-SYNC' : '⚠ DIVERGENT'}
                    </span>
                  </div>
                ))}
              </div>
            </div>
          </>
        )}
      </div>
    </div>
  );
};
