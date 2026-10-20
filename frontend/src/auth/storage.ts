const TOKEN_KEY = 'tokenx.accessToken';

// Browsers provide localStorage. Some isolated runtimes (SSR-like environments, headless test
// runners without web storage) do not, so a per-process map keeps the auth flow working instead of
// throwing while rendering.
const memory = new Map<string, string>();

function persistentStorage(): Storage | null {
  try {
    return typeof window !== 'undefined' && window.localStorage ? window.localStorage : null;
  } catch {
    return null;
  }
}

// The token lives in localStorage so a page reload keeps the session. It is read by the HTTP
// interceptor only; no component passes it around.
export function getToken(): string | null {
  const stored = persistentStorage()?.getItem(TOKEN_KEY);
  return stored ?? memory.get(TOKEN_KEY) ?? null;
}

export function setToken(token: string): void {
  const storage = persistentStorage();
  if (storage) {
    storage.setItem(TOKEN_KEY, token);
  } else {
    memory.set(TOKEN_KEY, token);
  }
}

export function clearToken(): void {
  persistentStorage()?.removeItem(TOKEN_KEY);
  memory.delete(TOKEN_KEY);
}
