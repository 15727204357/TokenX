import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react';
import { fetchMe, login, register, type TokenResponse, type User } from '../api/auth';
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

  // A token only becomes a session once /users/me confirms whose token it is. If that second step
  // fails (expired token, user disabled, server error), the token is dropped instead of being left
  // behind: a stored credential nobody successfully used would be retried on every page load.
  const authenticate = useCallback(async (obtainToken: () => Promise<TokenResponse>) => {
    const token = await obtainToken();
    setToken(token.accessToken);
    try {
      setUser(await fetchMe());
    } catch (error) {
      clearToken();
      setUser(null);
      throw error;
    }
  }, []);

  const signIn = useCallback(
    (username: string, password: string) => authenticate(() => login(username, password)),
    [authenticate],
  );

  const signUp = useCallback(
    (username: string, password: string) =>
      authenticate(async () => {
        await register(username, password);
        return login(username, password);
      }),
    [authenticate],
  );

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
