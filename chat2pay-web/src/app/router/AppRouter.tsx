import { Suspense, lazy } from 'react';
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom';
import { useAuthStore } from '@/features/auth/useAuthStore';
import { BrandLoadingPanel } from '@/shared/ui/BrandLoadingPanel';

const ChatWorkspacePage = lazy(() =>
  import('@/pages/chat-workspace/ChatWorkspacePage').then((module) => ({
    default: module.ChatWorkspacePage,
  })),
);

const ProfileSelectorPage = lazy(() =>
  import('@/pages/profile-selector/ProfileSelectorPage').then((module) => ({
    default: module.ProfileSelectorPage,
  })),
);

function RouteFallback() {
  return (
    <div className="brand-shell flex min-h-screen items-center justify-center p-6">
      <div className="w-full max-w-3xl">
        <BrandLoadingPanel
          title="Loading page"
          description="The next workspace view is being prepared."
        />
      </div>
    </div>
  );
}

function ProtectedRoute({ children }: { children: JSX.Element }) {
  const currentUser = useAuthStore((state) => state.currentUser);

  if (!currentUser) {
    return <Navigate to="/" replace />;
  }

  return children;
}

function RootRoute() {
  const currentUser = useAuthStore((state) => state.currentUser);

  if (currentUser) {
    return <Navigate to="/chat" replace />;
  }

  return (
    <Suspense fallback={<RouteFallback />}>
      <ProfileSelectorPage />
    </Suspense>
  );
}

export function AppRouter() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/" element={<RootRoute />} />
        <Route
          path="/chat"
          element={
            <ProtectedRoute>
              <Suspense fallback={<RouteFallback />}>
                <ChatWorkspacePage />
              </Suspense>
            </ProtectedRoute>
          }
        />
        <Route
          path="/chat/:sessionId"
          element={
            <ProtectedRoute>
              <Suspense fallback={<RouteFallback />}>
                <ChatWorkspacePage />
              </Suspense>
            </ProtectedRoute>
          }
        />
      </Routes>
    </BrowserRouter>
  );
}
