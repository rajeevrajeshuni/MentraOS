import React, { useState } from "react";
import { Button } from "./ui/button";
import { Input } from "./ui/input";
import { Label } from "./ui/label";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
  DialogFooter,
} from "./ui/dialog";
import { useAuth } from '../hooks/useAuth';

interface EmailAuthModalProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  redirectPath: string;
}

const EmailAuthModal: React.FC<EmailAuthModalProps> = ({
  open,
  onOpenChange,
  redirectPath,
}) => {
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [isSignUp, setIsSignUp] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [message, setMessage] = useState<string | null>(null);

  const { signIn, signUp } = useAuth();

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    setMessage(null);
    setLoading(true);

    try {
      if (isSignUp) {
        // Handle sign up
        const { error: signUpError } = await signUp(email, password, redirectPath);

        if (signUpError) {
          setError(signUpError.toString());
        } else {
          setMessage("Account created! Check your email for confirmation.");
        }
      } else {
        // Handle sign in
        const { data, error: signInError } =
          await signIn(email,password)

        if (signInError) {
          setError(signInError.toString());
        } else if (data?.session) {
          // Successfully logged in, close the modal and let Login Page handle redirect
          setMessage("Login successful! Redirecting...");
          // Close the modal after a brief delay
          setTimeout(() => {
            onOpenChange(false);
            // The LoginPage will handle the redirect
          }, 500);
        }
      }
    } catch (e) {
      setError("An unexpected error occurred. Please try again.");
      console.error(e);
    } finally {
      setLoading(false);
    }
  };

  const toggleAuthMode = () => {
    setIsSignUp(!isSignUp);
    setError(null);
    setMessage(null);
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>
            {isSignUp ? "Create an Account" : "Sign In with Email"}
          </DialogTitle>
          <DialogDescription>
            {isSignUp
              ? "Enter your details to create a new account"
              : "Enter your email and password to sign in"}
          </DialogDescription>
        </DialogHeader>

        <form onSubmit={handleSubmit}>
          <div className="grid gap-4 py-4">
            <div className="grid gap-2">
              <Label htmlFor="email">Email</Label>
              <Input
                id="email"
                type="email"
                placeholder="your.email@example.com"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                required
              />
            </div>

            <div className="grid gap-2">
              <Label htmlFor="password">Password</Label>
              <Input
                id="password"
                type="password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                required
              />
            </div>

            {error && <div className="text-sm text-red-500 mt-2">{error}</div>}

            {message && (
              <div className="text-sm text-green-600 mt-2">{message}</div>
            )}
          </div>

          <DialogFooter className="flex flex-col gap-2 sm:flex-row sm:justify-between sm:space-x-0">
            <Button
              type="button"
              variant="outline"
              onClick={toggleAuthMode}
              disabled={loading}
            >
              {isSignUp ? "Already have an account?" : "Need an account?"}
            </Button>

            <Button type="submit" disabled={loading}>
              {loading
                ? "Processing..."
                : isSignUp
                  ? "Create Account"
                  : "Sign In"}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
};

export default EmailAuthModal;
