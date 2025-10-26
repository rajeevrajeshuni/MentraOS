import React from 'react';

// Export all your shared components, utils, hooks, etc.
export const SharedButton: React.FC = () => {
  return <button>Click Me (Shared)</button>;
};

// Export useAuth hook
export { useAuth } from './auth/hooks/useAuth';

// Export AuthProvider

export { AuthProvider } from './auth/context/AuthContext'

// Export EmailModal component
export { default as EmailAuthModal } from './auth/components/EmailAuthModal';

//Export supabase object
export {supabase} from './auth/utils/supabase';

//Export LoginUI

export {LoginUI} from './auth/components/LoginUI';