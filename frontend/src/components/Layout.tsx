import { useState } from 'react';
import { Link, useNavigate, useLocation } from 'react-router-dom';
import {
  Menu,
  X,
  LogOut,
  LayoutDashboard,
  Briefcase,
  CreditCard,
  Wallet,
} from 'lucide-react';
import { useAuthStore } from '../store/authStore';
import { useBilling } from '../hooks/useBilling';

interface LayoutProps {
  children: React.ReactNode;
}

export const Layout = ({ children }: LayoutProps) => {
  const [sidebarOpen, setSidebarOpen] = useState(false);
  const navigate = useNavigate();
  const location = useLocation();
  const { user, logout } = useAuthStore();
  const { data: billingInfo } = useBilling().getBalance;

  const handleLogout = () => {
    logout();
    navigate('/login');
  };

  const isActive = (path: string) => location.pathname === path;

  const navItems = [
    { icon: LayoutDashboard, label: 'Dashboard', path: '/dashboard' },
    { icon: Briefcase, label: 'Job Openings', path: '/jobs' },
    { icon: CreditCard, label: 'Billing', path: '/billing' },
  ];

  const walletBalance = billingInfo
    ? (billingInfo.balancePaise / 100).toFixed(2)
    : '0.00';

  return (
    <div className="flex h-screen bg-gray-50">
      {/* Sidebar */}
      <aside
        className={`${
          sidebarOpen ? 'translate-x-0' : '-translate-x-full'
        } fixed inset-y-0 left-0 z-50 w-64 bg-navy text-white shadow-lg transition-transform lg:translate-x-0 lg:static lg:flex lg:flex-col`}
      >
        <div className="p-6 border-b border-navy-light">
          <h1 className="text-2xl font-bold">InterviewIQ</h1>
          <p className="text-navy-light text-sm mt-1">{user?.companyName}</p>
        </div>

        <nav className="flex-1 overflow-y-auto p-4">
          {navItems.map((item) => (
            <Link
              key={item.path}
              to={item.path}
              onClick={() => setSidebarOpen(false)}
              className={`flex items-center gap-3 px-4 py-3 rounded-lg mb-2 transition-colors ${
                isActive(item.path)
                  ? 'bg-blue-500 text-white'
                  : 'text-navy-light hover:bg-navy-light'
              }`}
            >
              <item.icon size={20} />
              <span>{item.label}</span>
            </Link>
          ))}
        </nav>

        <div className="p-4 border-t border-navy-light">
          <button
            onClick={handleLogout}
            className="flex items-center gap-3 px-4 py-3 text-navy-light hover:bg-navy-light rounded-lg w-full transition-colors"
          >
            <LogOut size={20} />
            <span>Logout</span>
          </button>
        </div>
      </aside>

      {/* Main content */}
      <div className="flex-1 flex flex-col overflow-hidden">
        {/* Top navbar */}
        <header className="bg-white border-b border-gray-200 shadow-sm">
          <div className="px-6 py-4 flex items-center justify-between">
            <button
              onClick={() => setSidebarOpen(!sidebarOpen)}
              className="lg:hidden p-2 hover:bg-gray-100 rounded-lg"
            >
              {sidebarOpen ? <X size={24} /> : <Menu size={24} />}
            </button>

            <h2 className="text-xl font-semibold text-gray-800 hidden sm:block">
              {navItems.find((item) => isActive(item.path))?.label || 'Dashboard'}
            </h2>

            <div className="flex items-center gap-4">
              <div className="flex items-center gap-2 bg-gray-100 px-4 py-2 rounded-lg">
                <Wallet size={18} className="text-blue-500" />
                <span className="text-sm font-medium text-gray-700">
                  ₹{walletBalance}
                </span>
              </div>
              <div className="text-sm">
                <p className="font-medium text-gray-900">{user?.name}</p>
                <p className="text-gray-500">{user?.email}</p>
              </div>
            </div>
          </div>
        </header>

        {/* Content */}
        <main className="flex-1 overflow-auto">
          <div className="p-6">{children}</div>
        </main>
      </div>

      {/* Mobile overlay */}
      {sidebarOpen && (
        <div
          className="fixed inset-0 bg-black bg-opacity-50 z-40 lg:hidden"
          onClick={() => setSidebarOpen(false)}
        ></div>
      )}
    </div>
  );
};
