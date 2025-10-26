import React from 'react';

// Export all your shared components, utils, hooks, etc.
export const SharedButton: React.FC = () => {
  return <button>Click Me (Shared)</button>;
};

// Export useAuth hook
export { useAuth } from './hooks/useAuth';

// Export AuthProvider

export { AuthProvider } from './context/AuthContext'

// Export EmailModal component
export { default as EmailAuthModal } from './components/EmailAuthModal';

//Export supabase object
export {supabase} from './utils/supabase';