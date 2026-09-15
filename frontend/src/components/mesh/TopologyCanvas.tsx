import React, { useMemo } from 'react';
import { VirtualDeviceSummary } from '../../types';
import { calculateNodePositions, generateMeshLinks, LinkDescriptor } from '../../utils/topologyLayout';
import { DeviceNode } from './DeviceNode';
import { MeshLink } from './MeshLink';

interface TopologyCanvasProps {
  devices: VirtualDeviceSummary[];
  severedLinks: string[];
  selectedDeviceId: string | null;
  onNodeClick: (deviceId: string) => void;
  onLinkClick: (link: LinkDescriptor) => void;
  disabled?: boolean;
}

export const TopologyCanvas: React.FC<TopologyCanvasProps> = ({
  devices,
  severedLinks,
  selectedDeviceId,
  onNodeClick,
  onLinkClick,
  disabled = false
}) => {
  const positionedNodes = useMemo(() => {
    return calculateNodePositions(devices, 720, 420);
  }, [devices]);

  const meshLinks = useMemo(() => {
    return generateMeshLinks(positionedNodes, severedLinks);
  }, [positionedNodes, severedLinks]);

  return (
    <div className="w-full bg-[#0d1117] border border-[#30363d] rounded-xl p-4 overflow-x-auto">
      <svg
        viewBox="0 0 720 420"
        className="w-full h-auto max-h-[460px] mx-auto select-none"
      >
        <defs>
          <linearGradient id="syncGradient" x1="0%" y1="0%" x2="100%" y2="100%">
            <stop offset="0%" stopColor="#38bdf8" />
            <stop offset="100%" stopColor="#10b981" />
          </linearGradient>
        </defs>

        {/* Render Links First (behind nodes) */}
        <g className="mesh-links">
          {meshLinks.map((link) => (
            <MeshLink
              key={link.key}
              link={link}
              onClick={onLinkClick}
              disabled={disabled}
            />
          ))}
        </g>

        {/* Render Device Nodes */}
        <g className="mesh-nodes">
          {positionedNodes.map((node) => (
            <DeviceNode
              key={node.deviceId}
              node={node}
              isSelected={selectedDeviceId === node.deviceId}
              onClick={onNodeClick}
            />
          ))}
        </g>
      </svg>
    </div>
  );
};
