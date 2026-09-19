import React from 'react';
import { PositionedNode } from '../../utils/topologyLayout';
import { truncateHash } from '../../utils/formatters';

interface DeviceNodeProps {
  node: PositionedNode;
  isSelected: boolean;
  onClick: (deviceId: string) => void;
}

export const DeviceNode: React.FC<DeviceNodeProps> = ({
  node,
  isSelected,
  onClick
}) => {
  const isBridge = node.hasInternet;

  return (
    <g
      transform={`translate(${node.x}, ${node.y})`}
      onClick={() => onClick(node.deviceId)}
      className="cursor-pointer group select-none"
    >
      {/* Selection / Halo */}
      {isSelected && (
        <circle
          r="42"
          fill="none"
          stroke="#10b981"
          strokeWidth="2.5"
          strokeDasharray="4 4"
          className="animate-spin-slow"
        />
      )}

      {/* Main Node Circle */}
      <circle
        r="32"
        fill={isBridge ? '#064e3b' : '#1e293b'}
        stroke={isBridge ? '#10b981' : isSelected ? '#38bdf8' : '#475569'}
        strokeWidth="2"
        className="transition-all duration-200 group-hover:stroke-emerald-400 group-hover:scale-105"
      />

      {/* 4G Icon Indicator for Bridge */}
      {isBridge && (
        <g transform="translate(14, -28)">
          <rect width="26" height="14" rx="4" fill="#10b981" />
          <text
            x="13"
            y="10"
            textAnchor="middle"
            fill="#064e3b"
            fontSize="9"
            fontWeight="bold"
            fontFamily="sans-serif"
          >
            4G
          </text>
        </g>
      )}

      {/* Device Name Label */}
      <text
        y="4"
        textAnchor="middle"
        fill="#f1f5f9"
        fontSize="11"
        fontWeight="600"
        fontFamily="sans-serif"
      >
        {node.deviceId.replace('phone-', '')}
      </text>

      {/* Packet Count Badge */}
      <g transform="translate(0, 20)">
        <rect
          x="-24"
          y="-8"
          width="48"
          height="14"
          rx="7"
          fill="#0f172a"
          stroke="#334155"
          strokeWidth="1"
        />
        <text
          y="2.5"
          textAnchor="middle"
          fill="#94a3b8"
          fontSize="9"
          fontWeight="500"
          fontFamily="monospace"
        >
          {node.packetCount} pkts
        </text>
      </g>

      {/* Truncated State Digest Pill */}
      <text
        y="46"
        textAnchor="middle"
        fill="#64748b"
        fontSize="9.5"
        fontFamily="monospace"
      >
        {truncateHash(node.stateDigest, 4, 4)}
      </text>
    </g>
  );
};
