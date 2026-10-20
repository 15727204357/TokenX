import { http } from './http';

export interface User {
  id: number;
  username: string;
  role: 'USER' | 'ADMIN';
}

export interface TokenResponse {
  accessToken: string;
  tokenType: string;
  expiresInSeconds: number;
}

export async function login(username: string, password: string): Promise<TokenResponse> {
  const { data } = await http.post<TokenResponse>('/auth/login', { username, password });
  return data;
}

export async function register(username: string, password: string): Promise<User> {
  const { data } = await http.post<User>('/auth/register', { username, password });
  return data;
}

export async function fetchMe(): Promise<User> {
  const { data } = await http.get<User>('/users/me');
  return data;
}
