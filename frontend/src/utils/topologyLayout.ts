import { VirtualDeviceSummary } from '../types';
import { KNOWN_NODE_COORDINATES } from './constants';

export interface PositionedNode extends VirtualDeviceSummary {
  x: number;
  y: number;
}

export interface LinkDescriptor {
  key: string;
  sourceId: string;
  targetId: string;
  sourceX: number;
  sourceY: number;
  targetX: number;
  targetY: number;
  isSevered: boolean;
}

/**
 * Calculates deterministic (x, y) coordinates for all nodes returned by the backend.
 * Uses known preset coordinates when available, and falls back to a deterministic
 * circular layout for any additional or unexpected device IDs.
 */
export function calculateNodePositions(
  devices: VirtualDeviceSummary[],
  width = 720,
  height = 420
): PositionedNode[] {
  const centerX = width / 2;
  const centerY = height / 2;
  const radius = Math.min(width, height) * 0.38;

  return devices.map((d, index) => {
    if (KNOWN_NODE_COORDINATES[d.deviceId]) {
      return {
        ...d,
        x: KNOWN_NODE_COORDINATES[d.deviceId].x,
        y: KNOWN_NODE_COORDINATES[d.deviceId].y
      };
    }

    // Dynamic circular fallback for unknown / additional devices
    const angle = (2 * Math.PI * index) / devices.length - Math.PI / 2;
    return {
      ...d,
      x: Math.round(centerX + radius * Math.cos(angle)),
      y: Math.round(centerY + radius * Math.sin(angle))
    };
  });
}

/**
 * Generates all unique pairwise links between positioned nodes,
 * marking whether each link is severed according to the severedLinks set.
 */
export function generateMeshLinks(
  nodes: PositionedNode[],
  severedLinks: string[]
): LinkDescriptor[] {
  const links: LinkDescriptor[] = [];
  const severedSet = new Set(severedLinks);

  for (let i = 0; i < nodes.length; i++) {
    for (let j = i + 1; j < nodes.length; j++) {
      const src = nodes[i];
      const dst = nodes[j];
      const key1 = `${src.deviceId}<->${dst.deviceId}`;
      const key2 = `${dst.deviceId}<->${src.deviceId}`;
      const isSevered = severedSet.has(key1) || severedSet.has(key2);

      links.push({
        key: src.deviceId < dst.deviceId ? key1 : key2,
        sourceId: src.deviceId,
        targetId: dst.deviceId,
        sourceX: src.x,
        sourceY: src.y,
        targetX: dst.x,
        targetY: dst.y,
        isSevered
      });
    }
  }

  return links;
}
