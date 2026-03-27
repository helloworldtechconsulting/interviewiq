import { useState } from 'react';
import { CreditCard, AlertCircle, TrendingDown, TrendingUp, Wallet } from 'lucide-react';
import { useBilling } from '../hooks/useBilling';
import { LoadingSpinner } from '../components/LoadingSpinner';
import { format } from 'date-fns';

declare global {
  interface Window {
    Razorpay: any;
  }
}

const TOPUP_AMOUNTS = [
  { label: '₹500', value: 50000 },
  { label: '₹1,000', value: 100000 },
  { label: '₹2,500', value: 250000 },
  { label: '₹5,000', value: 500000 },
  { label: '₹10,000', value: 1000000 },
];

export const BillingPage = () => {
  const { getBalance, getTransactions, initiateTopup, verifyTopup } = useBilling();
  const { data: billingInfo, isLoading: balanceLoading } = getBalance;
  const { data: transactions, isLoading: transactionsLoading } = getTransactions;

  const [customAmount, setCustomAmount] = useState('');
  const [selectedAmount, setSelectedAmount] = useState<number | null>(null);
  const [showCustom, setShowCustom] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleTopup = async (amount: number) => {
    setError(null);

    if (amount < 10000) {
      setError('Minimum top-up amount is ₹100');
      return;
    }

    try {
      const result = await initiateTopup.mutateAsync({ amountPaise: amount });
      const { orderId, key } = result.data.data;

      // Initialize Razorpay
      const options = {
        key: key,
        amount: amount,
        currency: 'INR',
        name: 'InterviewIQ',
        description: 'Wallet Top-up',
        order_id: orderId,
        handler: async (response: any) => {
          // Verify payment
          await verifyTopup.mutateAsync({
            razorpayOrderId: orderId,
            razorpayPaymentId: response.razorpay_payment_id,
            razorpaySignature: response.razorpay_signature,
          });
        },
        prefill: {
          contact: '9999999999',
          email: 'user@example.com',
        },
        theme: {
          color: '#2563EB',
        },
      };

      const razorpay = new window.Razorpay(options);
      razorpay.open();
    } catch (err: any) {
      setError(err.response?.data?.message || 'Failed to initiate payment');
    }
  };

  if (balanceLoading || transactionsLoading) return <LoadingSpinner />;

  const balanceRupees = billingInfo
    ? (billingInfo.balancePaise / 100).toFixed(2)
    : '0.00';

  return (
    <div className="space-y-6">
      {/* Header */}
      <div>
        <h1 className="text-3xl font-bold text-gray-900">Billing & Wallet</h1>
        <p className="text-gray-600 mt-1">Manage your account balance and payments</p>
      </div>

      {error && (
        <div className="p-4 bg-red-50 border border-red-200 rounded-lg flex gap-3">
          <AlertCircle size={20} className="text-red-600 flex-shrink-0" />
          <p className="text-red-700 text-sm">{error}</p>
        </div>
      )}

      {/* Balance Card */}
      <div className="bg-gradient-to-br from-blue-500 to-blue-600 rounded-lg shadow-lg p-8 text-white">
        <div className="flex items-center justify-between mb-8">
          <div>
            <p className="text-blue-100 text-sm">Current Balance</p>
            <p className="text-5xl font-bold mt-2">₹{balanceRupees}</p>
          </div>
          <Wallet size={48} className="opacity-80" />
        </div>

        <p className="text-blue-100 text-sm">
          {billingInfo && billingInfo.balancePaise > 0
            ? `You have sufficient balance for interviews`
            : 'Top up your wallet to schedule interviews'}
        </p>
      </div>

      {/* Top-up Section */}
      <div className="bg-white rounded-lg shadow-sm border border-gray-100 p-8">
        <h2 className="text-2xl font-bold text-gray-900 mb-6">Top Up Wallet</h2>

        {/* Preset Amounts */}
        <div className="grid grid-cols-2 md:grid-cols-5 gap-3 mb-6">
          {TOPUP_AMOUNTS.map((amount) => (
            <button
              key={amount.value}
              onClick={() => {
                setSelectedAmount(amount.value);
                setShowCustom(false);
                handleTopup(amount.value);
              }}
              disabled={initiateTopup.isPending}
              className="p-4 border-2 border-gray-200 hover:border-blue-500 rounded-lg font-semibold text-gray-900 hover:bg-blue-50 transition-colors disabled:opacity-50"
            >
              {amount.label}
            </button>
          ))}
        </div>

        {/* Custom Amount */}
        <div className="border-t border-gray-200 pt-6">
          <button
            onClick={() => setShowCustom(!showCustom)}
            className="text-blue-500 hover:text-blue-600 font-medium text-sm"
          >
            {showCustom ? 'Cancel' : 'Custom Amount'}
          </button>

          {showCustom && (
            <div className="mt-4 flex gap-3">
              <div className="flex-1 relative">
                <span className="absolute left-3 top-3 text-gray-700 font-medium">
                  ₹
                </span>
                <input
                  type="number"
                  value={customAmount}
                  onChange={(e) => setCustomAmount(e.target.value)}
                  placeholder="Enter amount"
                  className="w-full pl-8 pr-4 py-2 border border-gray-300 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-500"
                  min="100"
                />
              </div>
              <button
                onClick={() => {
                  const amountPaise = Math.floor(parseFloat(customAmount) * 100);
                  handleTopup(amountPaise);
                }}
                disabled={
                  !customAmount ||
                  initiateTopup.isPending ||
                  parseFloat(customAmount) < 100
                }
                className="px-6 py-2 bg-blue-500 hover:bg-blue-600 disabled:bg-gray-400 text-white rounded-lg font-medium transition-colors"
              >
                Top Up
              </button>
            </div>
          )}
        </div>
      </div>

      {/* Transaction History */}
      <div className="bg-white rounded-lg shadow-sm border border-gray-100 p-8">
        <h2 className="text-2xl font-bold text-gray-900 mb-6">
          Transaction History
        </h2>

        {!transactions || transactions.length === 0 ? (
          <div className="text-center py-12">
            <CreditCard size={48} className="mx-auto text-gray-300 mb-4" />
            <p className="text-gray-600 font-medium">No transactions yet</p>
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full">
              <thead>
                <tr className="border-b border-gray-200">
                  <th className="text-left py-3 px-4 font-semibold text-gray-900">
                    Date
                  </th>
                  <th className="text-left py-3 px-4 font-semibold text-gray-900">
                    Description
                  </th>
                  <th className="text-left py-3 px-4 font-semibold text-gray-900">
                    Type
                  </th>
                  <th className="text-right py-3 px-4 font-semibold text-gray-900">
                    Amount
                  </th>
                  <th className="text-right py-3 px-4 font-semibold text-gray-900">
                    Balance
                  </th>
                  <th className="text-left py-3 px-4 font-semibold text-gray-900">
                    Status
                  </th>
                </tr>
              </thead>
              <tbody>
                {transactions.map((txn) => (
                  <tr
                    key={txn.id}
                    className="border-b border-gray-200 hover:bg-gray-50"
                  >
                    <td className="py-4 px-4 text-gray-600 text-sm">
                      {format(new Date(txn.createdAt), 'MMM dd, yyyy HH:mm')}
                    </td>
                    <td className="py-4 px-4 text-gray-900 font-medium">
                      {txn.description}
                    </td>
                    <td className="py-4 px-4">
                      <div className="flex items-center gap-2">
                        {txn.type === 'TOPUP' ? (
                          <TrendingUp size={18} className="text-green-500" />
                        ) : (
                          <TrendingDown size={18} className="text-red-500" />
                        )}
                        <span
                          className={
                            txn.type === 'TOPUP'
                              ? 'text-green-700'
                              : 'text-red-700'
                          }
                        >
                          {txn.type}
                        </span>
                      </div>
                    </td>
                    <td
                      className={`py-4 px-4 text-right font-semibold ${
                        txn.type === 'TOPUP'
                          ? 'text-green-600'
                          : 'text-red-600'
                      }`}
                    >
                      {txn.type === 'TOPUP' ? '+' : '-'}₹
                      {(txn.amountPaise / 100).toFixed(2)}
                    </td>
                    <td className="py-4 px-4 text-right text-gray-900 font-medium">
                      ₹{(txn.balancePaise / 100).toFixed(2)}
                    </td>
                    <td className="py-4 px-4">
                      <span
                        className={`px-3 py-1 rounded-full text-sm font-medium ${
                          txn.status === 'SUCCESS'
                            ? 'bg-green-100 text-green-800'
                            : txn.status === 'PENDING'
                              ? 'bg-yellow-100 text-yellow-800'
                              : 'bg-red-100 text-red-800'
                        }`}
                      >
                        {txn.status}
                      </span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {/* Cost Information */}
      <div className="bg-blue-50 border border-blue-200 rounded-lg p-6">
        <h3 className="font-semibold text-blue-900 mb-3">Pricing</h3>
        <p className="text-blue-800 text-sm mb-2">
          Interview sessions are deducted from your wallet at the following rate:
        </p>
        <ul className="text-blue-800 text-sm space-y-1">
          <li>• Standard Interview (30 min): ₹500</li>
          <li>• Extended Interview (60 min): ₹750</li>
          <li>• Bulk Interview (10+ sessions): 10% discount</li>
        </ul>
      </div>
    </div>
  );
};
