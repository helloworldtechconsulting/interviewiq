import { BrowserRouter as Router, Routes, Route, Navigate } from 'react-router-dom';
import { QueryClientProvider, QueryClient } from '@tanstack/react-query';

// Pages
import { LoginPage } from './pages/LoginPage';
import { RegisterPage } from './pages/RegisterPage';
import { DashboardPage } from './pages/DashboardPage';
import { JobsPage } from './pages/JobsPage';
import { CreateJobPage } from './pages/CreateJobPage';
import { JobDetailPage } from './pages/JobDetailPage';
import { SessionDetailPage } from './pages/SessionDetailPage';
import { InterviewRoomPage } from './pages/InterviewRoomPage';
import { SchedulePage } from './pages/SchedulePage';
import { BillingPage } from './pages/BillingPage';

// Components
import { Layout } from './components/Layout';
import { ProtectedRoute } from './components/ProtectedRoute';

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 1000 * 60 * 5, // 5 minutes
      gcTime: 1000 * 60 * 10, // 10 minutes
    },
  },
});

function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <Router>
        <Routes>
          {/* Auth Routes */}
          <Route path="/login" element={<LoginPage />} />
          <Route path="/register" element={<RegisterPage />} />

          {/* Candidate Routes (No Auth Required) */}
          <Route path="/interview/:token" element={<InterviewRoomPage />} />
          <Route path="/schedule/:token" element={<SchedulePage />} />

          {/* Protected Routes */}
          <Route
            path="/dashboard"
            element={
              <ProtectedRoute>
                <Layout>
                  <DashboardPage />
                </Layout>
              </ProtectedRoute>
            }
          />

          <Route
            path="/jobs"
            element={
              <ProtectedRoute>
                <Layout>
                  <JobsPage />
                </Layout>
              </ProtectedRoute>
            }
          />

          <Route
            path="/jobs/create"
            element={
              <ProtectedRoute>
                <Layout>
                  <CreateJobPage />
                </Layout>
              </ProtectedRoute>
            }
          />

          <Route
            path="/jobs/:id"
            element={
              <ProtectedRoute>
                <Layout>
                  <JobDetailPage />
                </Layout>
              </ProtectedRoute>
            }
          />

          <Route
            path="/sessions/:id"
            element={
              <ProtectedRoute>
                <Layout>
                  <SessionDetailPage />
                </Layout>
              </ProtectedRoute>
            }
          />

          <Route
            path="/billing"
            element={
              <ProtectedRoute>
                <Layout>
                  <BillingPage />
                </Layout>
              </ProtectedRoute>
            }
          />

          {/* Catch all */}
          <Route path="/" element={<Navigate to="/dashboard" replace />} />
          <Route path="*" element={<Navigate to="/dashboard" replace />} />
        </Routes>
      </Router>
    </QueryClientProvider>
  );
}

export default App;
