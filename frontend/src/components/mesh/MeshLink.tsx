import React from 'react';
import { LinkDescriptor } from '../../utils/topologyLayout';

interface MeshLinkProps {
  link: LinkDescriptor;
  onClick: (link: LinkDescriptor) => void;
  disabled?: boolean;
}

export const MeshLink: React.FC<MeshLinkProps> = ({
  link,
  onClick,
  disabled = false
}) => {
  const midX = (link.sourceX + link.targetX) / 2;
  const midY = (link.sourceY + link.targetY) / 2;

  return (
    <g
      onClick={() => !disabled && onClick(link)}
      className={`group ${disabled ? 'cursor-not-allowed' : 'cursor-pointer'}`}
    >
      {/* Invisible wider stroke for easier mouse interaction */}
      <line
        x1={link.sourceX}
        y1={link.sourceY}
        x2={link.targetX}
        y2={link.targetY}
        stroke="transparent"
        strokeWidth="14"
      />

      {/* Visible Link Line */}
      <line
        x1={link.sourceX}
        y1={link.sourceY}
        x2={link.targetX}
        y2={link.targetY}
        stroke={link.isSevered ? '#ef4444' : '#334155'}
        strokeWidth={link.isSevered ? '2.5' : '1.5'}
        strokeDasharray={link.isSevered ? '6 6' : undefined}
        className="transition-colors duration-150 group-hover:stroke-sky-400"
      />

      {/* Scissor Badge if Severed */}
      {link.isSevered && (
        <g transform={`translate(${midX}, ${midY})`}>
          <circle r="9" fill="#450a0a" stroke="#ef4444" strokeWidth="1" />
          <text
            y="3"
            textAnchor="middle"
            fontSize="10"
            fill="#f87171"
            className="select-none"
          >
            ✕
          </text>
        </g>
      )}
    </g>
  );
};
