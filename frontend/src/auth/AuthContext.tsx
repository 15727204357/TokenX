import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react';
import { fetchMe, login, register, type User } from '../api/auth';
import { AuthContext, type AuthContextValue } from './context';
import { clearToken, getToken, setToken } from './storage';

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [initializing, setInitializing] = useState(() => getToken() !== null);

  // On a fresh page load the token alone is not trusted: /users/me is the authority on who the
  // caller is, and a rejected token is discarded instead of being retried.
  useEffect(() => {
    if (!getToken()) {
      setInitializing(false);
      return;
    }
    let cancelled = false;
    fetchMe()
      .then((me) => {
        if (!cancelled) {
          setUser(me);
        }
      })
      .catch(() => {
        clearToken();
        if (!cancelled) {
          setUser(null);
        }
      })
      .finally(() => {
        if (!cancelled) {
          setInitializing(false);
        }
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const signIn = useCallback(async (username: string, password: string) => {
    const token = await login(username, password);
    setToken(token.accessToken);
    setUser(await fetchMe());
  }, []);

  const signUp = useCallback(async (username: string, password: string) => {
    await register(username, password);
    const token = await login(username, password);
    setToken(token.accessToken);
    setUser(await fetchMe());
  }, []);

  const signOut = useCallback(() => {
    clearToken();
    setUser(null);
  }, []);

  const value = useMemo<AuthContextValue>(
    () => ({ user, initializing, signIn, signUp, signOut }),
    [user, initializing, signIn, signUp, signOut],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}
