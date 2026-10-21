import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach } from 'vitest';
import { clearToken } from '../auth/storage';

// Node 26 ships its own global `localStorage`: an accessor that yields undefined unless the process
// was started with `--localstorage-file`. Vitest's jsdom environment only copies a jsdom global onto
// the test global when the runtime does not already define that name, and `localStorage` is not in
// its allow-list, so on Node 26 jsdom's storage is dropped. `window` is aliased to the same global
// object, which means `window.localStorage` — the accessor `auth/storage.ts` reads — resolves to
// Node's empty stub and web storage silently disappears from every test.
//
// Re-publish the storage of the jsdom window backing this test file, so application code and tests
// keep sharing exactly one store. The binding is re-created per test file, because a worker reuses
// the global object while each file gets a fresh jsdom window.
const jsdomWindow = (globalThis as { jsdom?: { window: { localStorage: Storage } } }).jsdom?.window;

if (jsdomWindow) {
  const storage = jsdomWindow.localStorage;

  Object.defineProperty(globalThis, 'localStorage', {
    configurable: true,
    get: () => storage,
  });
}

afterEach(() => {
  cleanup();
  clearToken();
});
