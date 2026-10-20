import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach } from 'vitest';
import { clearToken } from '../auth/storage';

afterEach(() => {
  cleanup();
  clearToken();
});
