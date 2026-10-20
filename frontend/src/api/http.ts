import axios, { type AxiosError } from 'axios';
import { getToken } from '../auth/storage';

interface ApiErrorBody {
  code?: string;
  message?: string;
  requestId?: string;
}

/** Failure returned by the API, already reduced to what the UI needs to decide what to show. */
export class ApiError extends Error {
  readonly status: number;
  readonly code: string;

  constructor(status: number, code: string, message: string) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
  }
}

export const http = axios.create({
  baseURL: '/api/v1',
  timeout: 10_000,
});

http.interceptors.request.use((config) => {
  const token = getToken();
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

http.interceptors.response.use(
  (response) => response,
  (error: AxiosError<ApiErrorBody>) => {
    const status = error.response?.status ?? 0;
    const body = error.response?.data;
    return Promise.reject(
      new ApiError(status, body?.code ?? 'NETWORK_ERROR', body?.message ?? 'request failed'),
    );
  },
);
