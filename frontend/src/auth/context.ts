import { createContext } from 'react';
import type { User } from '../api/auth';

export interface AuthContextValue {
  user: User | null;
  /** True while a stored token is being exchanged for the current user. */
  initializing: boolean;
  signIn: (username: string, password: string) => Promise<void>;
  signUp: (username: string, password: string) => Promise<void>;
  signOut: () => void;
}

export const AuthContext = createContext<AuthContextValue | null>(null);
