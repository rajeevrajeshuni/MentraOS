import {
  BrowserRouter as Router,
  Routes,
  Route,
  Navigate,
} from "react-router-dom";
import { Toaster } from "sonner";
import { AuthProvider, useAuth} from "@mentra/shared";
import LoginPage from "./pages/LoginPage";
import AccountPage from "./pages/AccountPage";
import DeleteAccountPage from "./pages/DeleteAccountPage";
import ExportDataPage from "./pages/ExportDataPage";
import AuthFlowPage from "./pages/AuthFlowPage";

// Protected route component
const ProtectedRoute = ({ children }: { children: React.ReactNode }) => {
  const { isAuthenticated, isLoading, user, session } = useAuth();

  // Don't redirect immediately while still loading
  if (isLoading) {
    return (
      <div className="flex items-center justify-center min-h-screen">
        <div className="animate-spin rounded-full h-12 w-12 border-t-2 border-b-2 border-blue-500"></div>
      </div>
    );
  }

  // Check for the core token as an additional authentication check
  const hasCoreToken = !!localStorage.getItem("core_token");

  // Only redirect when we're confident the user isn't authenticated
  if (!isAuthenticated && !isLoading && !user && !session && !hasCoreToken) {
    console.log("User not authenticated, redirecting to login");
    return <Navigate to="/login" replace />;
  }

  return <>{children}</>;
};

function App() {
  return (
    <AuthProvider>
      <Router>
        <Toaster position="top-right" richColors />
        <Routes>
          <Route path="/login" element={<LoginPage />} />

          {/* OAuth flow route - doesn't require ProtectedRoute wrapper as it handles auth internally */}
          <Route path="/auth" element={<AuthFlowPage />} />

          <Route
            path="/account"
            element={
              <ProtectedRoute>
                <AccountPage />
              </ProtectedRoute>
            }
          />

          <Route
            path="/account/delete"
            element={
              <ProtectedRoute>
                <DeleteAccountPage />
              </ProtectedRoute>
            }
          />

          <Route
            path="/account/export"
            element={
              <ProtectedRoute>
                <ExportDataPage />
              </ProtectedRoute>
            }
          />

          <Route path="/" element={<Navigate to="/account" replace />} />

          {/* Catch-all route */}
          <Route
            path="*"
            element={
              <ProtectedRoute>
                <Navigate to="/account" replace />
              </ProtectedRoute>
            }
          />
        </Routes>
      </Router>
    </AuthProvider>
  );
}

export default App;
