# InterviewIQ Frontend

A modern, professional React-based frontend for InterviewIQ, an AI-powered interview SaaS platform.

## Tech Stack

- **React 18** - UI framework
- **Vite** - Build tool & dev server
- **TypeScript** - Type safety
- **Tailwind CSS** - Utility-first CSS framework
- **React Router v6** - Client-side routing
- **TanStack Query (React Query)** - Server state management
- **Zustand** - Lightweight client state management
- **Axios** - HTTP client
- **Recharts** - Data visualization
- **Lucide React** - Icon library

## Project Structure

```
src/
├── api/
│   └── client.ts              # Axios instance with JWT interceptor
├── components/
│   ├── Layout.tsx             # Main layout with sidebar & navbar
│   ├── ProtectedRoute.tsx      # Auth guard component
│   ├── LoadingSpinner.tsx      # Loading indicator
│   ├── StatusBadge.tsx         # Status display component
│   ├── ScoreBadge.tsx          # Score badge component
│   ├── FileUpload.tsx          # Drag & drop file uploader
│   └── DimensionChart.tsx      # Bar chart for scores
├── hooks/
│   ├── useAuth.ts             # Authentication hook
│   ├── useJobs.ts             # Job management hook
│   ├── useSessions.ts         # Session management hook
│   └── useBilling.ts          # Billing hook
├── pages/
│   ├── LoginPage.tsx          # Login & password auth
│   ├── RegisterPage.tsx        # Company registration
│   ├── DashboardPage.tsx      # Main dashboard
│   ├── JobsPage.tsx           # Jobs listing
│   ├── CreateJobPage.tsx      # Create job form
│   ├── JobDetailPage.tsx      # Job details & candidates
│   ├── SessionDetailPage.tsx  # Interview evaluation report
│   ├── InterviewRoomPage.tsx  # Main interview interface (candidate-facing)
│   ├── SchedulePage.tsx       # Candidate slot selection
│   └── BillingPage.tsx        # Wallet & billing management
├── store/
│   └── authStore.ts           # Zustand auth store
├── types/
│   └── index.ts               # TypeScript type definitions
├── App.tsx                    # Main app component with routing
├── main.tsx                   # React entry point
└── index.css                  # Global styles & Tailwind imports
```

## Setup & Installation

### Prerequisites
- Node.js 18+ and npm/yarn
- Backend API running at http://localhost:8080

### Installation

1. Install dependencies:
```bash
npm install
```

2. Create `.env` file from `.env.example`:
```bash
cp .env.example .env
```

3. Update environment variables:
```env
VITE_API_URL=http://localhost:8080
VITE_GOOGLE_CLIENT_ID=your_google_client_id
VITE_RAZORPAY_KEY_ID=your_razorpay_key
```

### Development

Start the development server:
```bash
npm run dev
```

Server runs on `http://localhost:3000` with hot module reloading.

### Building

Build for production:
```bash
npm run build
```

Preview production build:
```bash
npm run preview
```

## Key Features

### Authentication
- Email/password login & registration
- JWT token management with automatic refresh
- Persistent authentication state via localStorage
- Protected routes with automatic redirection

### Employer Dashboard
- Overview of active jobs, interviews, and wallet balance
- Quick action buttons for common tasks
- Recent job openings and quick navigation

### Job Management
- Create new job openings with JD file upload
- List all jobs with status filtering
- View candidate list for each job
- Add candidates manually with resume upload
- Track interview progress and scores

### Interview Room (Candidate-Facing)
- Camera and microphone permissions
- Real-time speech recognition (Web Speech API)
- Question presentation with AI text-to-speech
- Video recording during interview
- Anti-cheat monitoring:
  - Tab switch detection
  - Camera status monitoring
  - Face presence detection
  - Live anti-cheat warnings

### Session Evaluation
- Overall score badge
- Dimension-wise scores with bar chart
- Full transcript with expandable Q&A pairs
- Video playback with expiry notice
- Editable employer notes
- Anti-cheat flags display

### Billing & Payments
- Real-time wallet balance display
- Razorpay integration for top-ups
- Flexible custom amounts
- Transaction history with status
- Pricing information display

## API Integration

The frontend integrates with the backend via:

1. **REST API** - for CRUD operations
2. **WebSocket** - for interview Q&A flow

### Authentication Flow
```
1. User logs in → JWT tokens received
2. Tokens stored in Zustand + localStorage
3. Axios interceptor adds JWT to all requests
4. On 401, refresh token is used to get new access token
5. Failed refresh redirects to login
```

### Interview Flow
1. Candidate opens interview link with token
2. Frontend verifies token with backend
3. WebSocket connects for Q&A
4. Backend sends questions one by one
5. Frontend handles speech recognition & synthesis
6. Candidate answers recorded and sent back
7. Video stream recorded during interview
8. After completion, video uploaded to backend

## Component Patterns

### Data Fetching
Uses TanStack Query hooks for optimal caching and state management:
```tsx
const { data, isLoading, error } = useJobs().getJobs;
```

### Form Handling
Controlled components with useState:
```tsx
const [formData, setFormData] = useState({ ... });
const handleChange = (e) => setFormData(prev => ({ ...prev, [e.target.name]: e.target.value }));
```

### Protected Routes
Wraps protected pages with ProtectedRoute component:
```tsx
<ProtectedRoute>
  <Layout>
    <DashboardPage />
  </Layout>
</ProtectedRoute>
```

## Styling

Uses Tailwind CSS with custom navy color scheme:
- Primary: Navy (#1A3A5C)
- Secondary: Blue (#2563EB)
- Light backgrounds and professional typography

## Error Handling

- API errors display in user-friendly alert boxes
- Network errors caught by axios interceptors
- Form validation with clear error messages
- Loading states prevent duplicate submissions

## Performance Optimizations

- Code splitting with React.lazy for routes
- Image optimization with proper formats
- CSS-in-JS with Tailwind (no runtime overhead)
- Debounced search queries
- Query caching with TanStack Query
- Static asset caching in production

## Browser Support

Modern browsers with:
- ES2020+ support
- Web Speech API (for speech recognition)
- WebRTC (for video streaming)
- MediaRecorder API (for video recording)

## Development Guidelines

1. **TypeScript** - Use strict mode, define types for all props
2. **Components** - Keep components small and focused
3. **Styling** - Use Tailwind utilities, avoid CSS files
4. **Data Fetching** - Use custom hooks with TanStack Query
5. **State Management** - Use Zustand for auth, React state for UI

## Deployment

### Docker
```bash
docker build -t interviewiq-frontend .
docker run -p 80:3000 interviewiq-frontend
```

### Production Build
The Dockerfile creates an optimized Nginx container:
- Multi-stage build for minimal image size
- Gzip compression enabled
- Cache-busting for static assets
- SPA routing with fallback to index.html

## License

Proprietary - InterviewIQ
