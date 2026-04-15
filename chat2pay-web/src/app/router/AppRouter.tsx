import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom';
import { ChatWorkspacePage } from '@/pages/chat-workspace/ChatWorkspacePage';
import { ProfileSelectorPage } from '@/pages/profile-selector/ProfileSelectorPage';
import { useAuthStore } from '@/features/auth/useAuthStore';

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

  return <ProfileSelectorPage />;
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
              <ChatWorkspacePage />
            </ProtectedRoute>
          }
        />
        <Route
          path="/chat/:sessionId"
          element={
            <ProtectedRoute>
              <ChatWorkspacePage />
            </ProtectedRoute>
          }
        />
      </Routes>
    </BrowserRouter>
  );
}
