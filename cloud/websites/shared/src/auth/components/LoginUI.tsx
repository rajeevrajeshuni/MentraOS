import React from 'react';
import { Auth } from '@supabase/auth-ui-react';
import { ThemeSupa } from '@supabase/auth-ui-shared';
import { supabase } from '../utils/supabase';
import { Button } from '../components/ui/button';
import EmailAuthModal from './EmailAuthModal';

interface LoginUIProps {
  /** Logo image URL */
  logoUrl?: string;
  /** Site name to display below logo */
  siteName: string;
  /** Optional message to display above sign-in options */
  message?: string;
  /** Redirect path after successful authentication */
  redirectTo: string;
  /** Email modal redirect path */
  emailRedirectPath: string;
  /** Email modal open state */
  isEmailModalOpen: boolean;
  /** Email modal state setter */
  setIsEmailModalOpen: (open: boolean) => void;
}

export const LoginUI: React.FC<LoginUIProps> = ({
  logoUrl = "https://imagedelivery.net/nrc8B2Lk8UIoyW7fY8uHVg/757b23a3-9ec0-457d-2634-29e28f03fe00/verysmall",
  siteName,
  message,
  redirectTo,
  emailRedirectPath,
  isEmailModalOpen,
  setIsEmailModalOpen,
}) => {
  return (
    <div className="min-h-screen bg-gray-50 flex flex-col">
      <main className="container mx-auto px-4 py-8 flex-1 flex flex-col items-center justify-center">
        <img src={logoUrl} alt="Mentra Logo" />
        <div className="max-w-lg w-full text-center mt-6 mb-6">
          <h1 className="text-2xl font-bold mb-2">
            Welcome to the MentraOS {siteName} Portal
          </h1>
          <div className="text-center mb-2">
            <p className="text-sm text-gray-500 mt-1">
              Choose your preferred sign in method
            </p>
            {message && (
              <p className="mt-4 text-sm text-blue-600 bg-blue-50 p-3 rounded-md">
                {message}
              </p>
            )}
          </div>
        </div>
        {/* --- Login Card --- */}
        <div className="max-w-md w-full bg-white p-8 rounded-lg shadow-md flex flex-col items-center">
          <div className="w-full space-y-4">
            {/* Social Provider Sign In */}
            <Auth
              supabaseClient={supabase}
              appearance={{
                theme: ThemeSupa,
                style: {
                  button: {
                    borderRadius: '4px',
                    fontSize: '14px',
                    fontWeight: '500',
                  },
                  anchor: {
                    display: 'none',
                  },
                  container: {
                    width: '100%',
                  },
                },
                className: {
                  message: 'hidden',
                  divider: 'hidden',
                  label: 'hidden',
                  input: 'hidden',
                },
              }}
              providers={['google', 'apple']}
              view="sign_in"
              redirectTo={redirectTo}
              showLinks={false}
              onlyThirdPartyProviders={true}
            />
            {/* Email Sign In Divider and Button */}
            <div className="w-full flex flex-col items-center space-y-4 mt-4">
              <div className="relative flex items-center w-full">
                <div className="flex-1 border-t border-gray-300"></div>
                <span className="px-4 text-sm text-gray-500">or</span>
                <div className="flex-1 border-t border-gray-300"></div>
              </div>

              <Button
                className="w-full py-2"
                onClick={() => setIsEmailModalOpen(true)}
                variant="outline"
              >
                Sign in with Email
              </Button>
            </div>
          </div>

          <div className="text-center text-sm text-gray-500 mt-6">
            <p>
              By signing in, you agree to our Terms of Service and Privacy Policy.
            </p>
          </div>
        </div>
      </main>
      {/* Email Auth Modal */}
      <EmailAuthModal
        open={isEmailModalOpen}
        onOpenChange={setIsEmailModalOpen}
        redirectPath={emailRedirectPath}
      />
    </div>
  );
};