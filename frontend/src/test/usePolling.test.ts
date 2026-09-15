import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { usePolling } from '../hooks/usePolling';

describe('usePolling Hook', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('performs initial fetch on mount', async () => {
    const fetcher = vi.fn().mockResolvedValue({ status: 'HEALTHY' });

    const { result } = renderHook(() =>
      usePolling({
        fetcher,
        activeIntervalMs: 2000
      })
    );

    expect(fetcher).toHaveBeenCalledTimes(1);

    await act(async () => {
      await Promise.resolve();
    });

    expect(result.current.data).toEqual({ status: 'HEALTHY' });
    expect(result.current.loading).toBe(false);
    expect(result.current.isStale).toBe(false);
  });

  it('triggers periodic fetch based on activeIntervalMs', async () => {
    const fetcher = vi.fn().mockResolvedValue({ count: 1 });

    renderHook(() =>
      usePolling({
        fetcher,
        activeIntervalMs: 2000
      })
    );

    expect(fetcher).toHaveBeenCalledTimes(1);

    // Allow initial fetch promise to resolve and register scheduleNext
    await act(async () => {
      await Promise.resolve();
    });

    // Advance to tick 1
    await act(async () => {
      vi.advanceTimersByTime(2000);
      await Promise.resolve();
    });

    expect(fetcher).toHaveBeenCalledTimes(2);

    // Advance to tick 2
    await act(async () => {
      vi.advanceTimersByTime(2000);
      await Promise.resolve();
    });

    expect(fetcher).toHaveBeenCalledTimes(3);
  });

  it('marks data as stale when threshold elapses without success', async () => {
    const fetcher = vi.fn().mockResolvedValue({ count: 1 });

    const { result } = renderHook(() =>
      usePolling({
        fetcher,
        activeIntervalMs: 1000,
        staleThresholdMs: 3000
      })
    );

    await act(async () => {
      await Promise.resolve();
    });

    expect(result.current.isStale).toBe(false);

    // Simulate failure on subsequent polls
    fetcher.mockRejectedValue(new Error('Network outage'));

    // Advance past stale threshold (e.g. 4000 ms > 3000 ms)
    await act(async () => {
      vi.advanceTimersByTime(4000);
      await Promise.resolve();
    });

    expect(result.current.isStale).toBe(true);
  });
});
