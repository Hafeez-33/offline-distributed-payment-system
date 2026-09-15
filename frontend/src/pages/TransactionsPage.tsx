import React, { useState, useEffect, useCallback } from 'react';
import { ReceiptText, Search, ChevronLeft, ChevronRight, RefreshCw } from 'lucide-react';
import { PaginatedTransactions, TransactionView } from '../types';
import { TransactionTable } from '../components/transactions/TransactionTable';
import { TxReceiptModal } from '../components/transactions/TxReceiptModal';
import { Card } from '../components/common/Card';
import { Button } from '../components/common/Button';
import { api } from '../services/api';

export const TransactionsPage: React.FC = () => {
  const [data, setData] = useState<PaginatedTransactions | null>(null);
  const [loading, setLoading] = useState<boolean>(true);
  const [page, setPage] = useState<number>(0);
  const [statusFilter, setStatusFilter] = useState<string>('ALL');
  const [searchQuery, setSearchQuery] = useState<string>('');
  const [selectedTx, setSelectedTx] = useState<TransactionView | null>(null);

  const fetchTransactions = useCallback(async () => {
    try {
      setLoading(true);
      const res = await api.getTransactions(page, 25, statusFilter, searchQuery);
      setData(res);
    } catch (err) {
      console.error('Failed to load transactions:', err);
    } finally {
      setLoading(false);
    }
  }, [page, statusFilter, searchQuery]);

  useEffect(() => {
    fetchTransactions();
  }, [fetchTransactions]);

  const handleStatusChange = (status: string) => {
    setStatusFilter(status);
    setPage(0);
  };

  const handleSearchChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    setSearchQuery(e.target.value);
    setPage(0);
  };

  const statusTabs = [
    { key: 'ALL', label: 'All Transactions' },
    { key: 'SETTLED', label: 'Settled' },
    { key: 'CONFLICTING', label: 'Conflicting / Forks' },
    { key: 'PENDING_SEQUENCE_GAP', label: 'Pending Gap' },
    { key: 'REJECTED', label: 'Rejected' }
  ];

  return (
    <div className="space-y-6">
      {/* Header & Description */}
      <div className="bg-[#161b22] border border-[#30363d] rounded-xl p-5 flex flex-col md:flex-row items-start md:items-center justify-between gap-4">
        <div>
          <h2 className="text-lg font-bold text-slate-100 flex items-center gap-2">
            <ReceiptText className="w-5 h-5 text-emerald-400" />
            <span>Authoritative Transaction Audit Ledger</span>
          </h2>
          <p className="text-xs text-slate-400 mt-1">
            Permanent database record of all processed packets. Idempotency is enforced by unique index
            on <code>packetHash</code>. Duplicate and conflicting submissions are preserved for forensic audit.
          </p>
        </div>

        <button
          onClick={fetchTransactions}
          disabled={loading}
          className="flex items-center gap-1.5 text-xs text-slate-300 hover:text-slate-100 bg-[#0d1117] border border-[#30363d] px-3 py-2 rounded-lg"
        >
          <RefreshCw className={`w-3.5 h-3.5 ${loading ? 'animate-spin' : ''}`} />
          <span>Refresh Ledger</span>
        </button>
      </div>

      {/* Filter and Search Bar */}
      <div className="flex flex-col md:flex-row items-stretch md:items-center justify-between gap-4">
        {/* Status Filter Tabs */}
        <div className="flex flex-wrap items-center bg-[#161b22] border border-[#30363d] p-1 rounded-xl gap-1">
          {statusTabs.map((tab) => (
            <button
              key={tab.key}
              onClick={() => handleStatusChange(tab.key)}
              className={`px-3 py-1.5 rounded-lg text-xs font-medium transition-all ${
                statusFilter === tab.key
                  ? 'bg-emerald-600 text-white font-semibold'
                  : 'text-slate-400 hover:text-slate-200'
              }`}
            >
              {tab.label}
            </button>
          ))}
        </div>

        {/* Search Input */}
        <div className="relative min-w-[280px]">
          <Search className="w-4 h-4 text-slate-400 absolute left-3 top-2.5" />
          <input
            type="text"
            placeholder="Search hash, VPA, wallet..."
            value={searchQuery}
            onChange={handleSearchChange}
            className="w-full bg-[#161b22] border border-[#30363d] rounded-xl pl-9 pr-3 py-2 text-xs text-slate-200 focus:outline-hidden focus:border-emerald-500"
          />
        </div>
      </div>

      {/* Transactions Table Card */}
      <Card>
        {loading ? (
          <div className="py-16 text-center text-slate-400 flex flex-col items-center justify-center space-y-2">
            <RefreshCw className="w-6 h-6 animate-spin text-emerald-400" />
            <span className="text-xs">Querying paginated transaction ledger...</span>
          </div>
        ) : (
          <TransactionTable
            transactions={data?.content ?? []}
            onSelectTx={(tx) => setSelectedTx(tx)}
          />
        )}

        {/* Pagination Bar */}
        {data && data.totalPages > 1 && (
          <div className="flex items-center justify-between pt-4 mt-4 border-t border-[#30363d] text-xs text-slate-400">
            <div>
              Showing {data.content.length} of {data.totalElements} records (Page {data.page + 1} of{' '}
              {data.totalPages})
            </div>
            <div className="flex items-center space-x-2">
              <Button
                variant="outline"
                disabled={data.page === 0}
                onClick={() => setPage((p) => Math.max(0, p - 1))}
                className="text-xs py-1 px-2.5"
              >
                <ChevronLeft className="w-4 h-4 mr-1" />
                Previous
              </Button>
              <Button
                variant="outline"
                disabled={data.page >= data.totalPages - 1}
                onClick={() => setPage((p) => p + 1)}
                className="text-xs py-1 px-2.5"
              >
                Next
                <ChevronRight className="w-4 h-4 ml-1" />
              </Button>
            </div>
          </div>
        )}
      </Card>

      {/* Transaction Receipt Modal */}
      <TxReceiptModal tx={selectedTx} onClose={() => setSelectedTx(null)} />
    </div>
  );
};
