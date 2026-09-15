/**
 * Formats a currency amount into standard Indian Rupee notation.
 */
export function formatCurrency(amount: number | string | null | undefined): string {
  if (amount == null) return '₹ 0.00';
  const num = typeof amount === 'string' ? parseFloat(amount) : amount;
  if (isNaN(num)) return '₹ 0.00';
  return '₹ ' + num.toLocaleString('en-IN', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2
  });
}

/**
 * Truncates a SHA-256 hash or packet ID with an ellipsis.
 */
export function truncateHash(hash: string | null | undefined, head = 8, tail = 4): string {
  if (!hash) return '-';
  if (hash.length <= head + tail + 3) return hash;
  return `${hash.substring(0, head)}...${hash.substring(hash.length - tail)}`;
}

/**
 * Formats an ISO timestamp into readable time and date.
 */
export function formatTimestamp(isoString: string | null | undefined): string {
  if (!isoString) return '-';
  try {
    const d = new Date(isoString);
    if (isNaN(d.getTime())) return isoString;
    return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
  } catch {
    return isoString;
  }
}
