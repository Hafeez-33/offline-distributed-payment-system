import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { TopologyCanvas } from '../components/mesh/TopologyCanvas';
import { VirtualDeviceSummary } from '../types';

describe('TopologyCanvas Component', () => {
  const mockDevices: VirtualDeviceSummary[] = [
    { deviceId: 'phone-alice', hasInternet: false, stateDigest: '4a7d1ed414474e4033ac29ccb8653d9b', packetCount: 3 },
    { deviceId: 'phone-bridge', hasInternet: true, stateDigest: '4a7d1ed414474e4033ac29ccb8653d9b', packetCount: 3 },
    { deviceId: 'phone-custom-node', hasInternet: false, stateDigest: '11223344556677889900aabbccddeeff', packetCount: 1 }
  ];

  it('renders all devices provided by the backend, including unknown nodes', () => {
    const handleNodeClick = vi.fn();
    const handleLinkClick = vi.fn();

    render(
      <TopologyCanvas
        devices={mockDevices}
        severedLinks={[]}
        selectedDeviceId={null}
        onNodeClick={handleNodeClick}
        onLinkClick={handleLinkClick}
      />
    );

    expect(screen.getByText('alice')).toBeInTheDocument();
    expect(screen.getByText('bridge')).toBeInTheDocument();
    expect(screen.getByText('custom-node')).toBeInTheDocument();
  });

  it('highlights 4G indicator for bridge nodes', () => {
    render(
      <TopologyCanvas
        devices={mockDevices}
        severedLinks={[]}
        selectedDeviceId={null}
        onNodeClick={vi.fn()}
        onLinkClick={vi.fn()}
      />
    );

    expect(screen.getByText('4G')).toBeInTheDocument();
  });

  it('renders severed links with severed visual indicator', () => {
    const severed = ['phone-alice<->phone-bridge'];
    const { container } = render(
      <TopologyCanvas
        devices={mockDevices}
        severedLinks={severed}
        selectedDeviceId={null}
        onNodeClick={vi.fn()}
        onLinkClick={vi.fn()}
      />
    );

    // Should render the scissor badge ✕
    expect(container.querySelector('text[fill="#f87171"]')).toBeInTheDocument();
  });

  it('triggers onNodeClick when a device is clicked', () => {
    const handleNodeClick = vi.fn();
    render(
      <TopologyCanvas
        devices={mockDevices}
        severedLinks={[]}
        selectedDeviceId={null}
        onNodeClick={handleNodeClick}
        onLinkClick={vi.fn()}
      />
    );

    fireEvent.click(screen.getByText('alice'));
    expect(handleNodeClick).toHaveBeenCalledWith('phone-alice');
  });
});
