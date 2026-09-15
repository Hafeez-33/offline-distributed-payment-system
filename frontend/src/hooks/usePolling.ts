import { useState, useEffect, useRef, useCallback } from 'react';

interface UsePollingOptions<T> {
  fetcher: () => Promise<T>;
  activeIntervalMs?: number;
  hiddenIntervalMs?: number;
  staleThresholdMs?: number;
  onSuccess?: (data: T) => void;
  onError?: (err: Error) => void;
}

export interface UsePollingResult<T> {
  data: T | null;
  loading: boolean;
  isStale: boolean;
  isDisconnected: boolean;
  lastUpdated: Date | null;
  error: Error | null;
  refresh: () => Promise<void>;
}

export function usePolling<T>({
  fetcher,
  activeIntervalMs = 2000,
  hiddenIntervalMs = 10000,
  staleThresholdMs = 5000,
  onSuccess,
  onError
}: UsePollingOptions<T>): UsePollingResult<T> {
  const [data, setData] = useState<T | null>(null);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<Error | null>(null);
  const [lastSuccessTime, setLastSuccessTime] = useState<number>(Date.now());
  const [lastUpdated, setLastUpdated] = useState<Date | null>(null);
  const [isStale, setIsStale] = useState<boolean>(false);
  const [isDisconnected, setIsDisconnected] = useState<boolean>(false);

  const fetcherRef = useRef(fetcher);
  fetcherRef.current = fetcher;

  const onSuccessRef = useRef(onSuccess);
  onSuccessRef.current = onSuccess;

  const onErrorRef = useRef(onError);
  onErrorRef.current = onError;

  const executeFetch = useCallback(async () => {
    try {
      const result = await fetcherRef.current();
      setData(result);
      const now = Date.now();
      setLastSuccessTime(now);
      setLastUpdated(new Date(now));
      setIsStale(false);
      setIsDisconnected(false);
      setError(null);
      if (onSuccessRef.current) onSuccessRef.current(result);
    } catch (err: unknown) {
      const e = err instanceof Error ? err : new Error(String(err));
      setError(e);
      setIsDisconnected(true);
      if (onErrorRef.current) onErrorRef.current(e);
    } finally {
      setLoading(false);
    }
  }, []);

  // Timer loop with visibility-aware adaptive interval
  useEffect(() => {
    let timeoutId: NodeJS.Timeout | null = null;

    const scheduleNext = () => {
      const isHidden = typeof document !== 'undefined' && document.visibilityState === 'hidden';
      const interval = isHidden ? hiddenIntervalMs : activeIntervalMs;

      timeoutId = setTimeout(async () => {
        await executeFetch();
        scheduleNext();
      }, interval);
    };

    // Initial fetch
    executeFetch().then(scheduleNext);

    return () => {
      if (timeoutId) clearTimeout(timeoutId);
    };
  }, [executeFetch, activeIntervalMs, hiddenIntervalMs]);

  // Periodic stale-data watchdog
  useEffect(() => {
    const watchdog = setInterval(() => {
      const elapsed = Date.now() - lastSuccessTime;
      if (elapsed > staleThresholdMs) {
        setIsStale(true);
      }
    }, 1000);

    return () => clearInterval(watchdog);
  }, [lastSuccessTime, staleThresholdMs]);

  const refresh = useCallback(async () => {
    await executeFetch();
  }, [executeFetch]);

  return {
    data,
    loading,
    isStale,
    isDisconnected,
    lastUpdated,
    error,
    refresh
  };
}
