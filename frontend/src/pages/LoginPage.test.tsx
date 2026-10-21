import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import App from '../App';
import { fetchMe, login, register } from '../api/auth';
import { ApiError } from '../api/http';
import { AuthProvider } from '../auth/AuthContext';
import { clearToken, getToken, setToken } from '../auth/storage';

vi.mock('../api/auth');

function renderApp(initialPath = '/login') {
  return render(
    <MemoryRouter initialEntries={[initialPath]}>
      <AuthProvider>
        <App />
      </AuthProvider>
    </MemoryRouter>,
  );
}

async function fillCredentials(username: string, password: string) {
  await userEvent.type(screen.getByLabelText('用户名'), username);
  await userEvent.type(screen.getByLabelText('密码'), password);
}

describe('登录流程', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    clearToken();
  });

  it('登录成功后保存 token 并从 /users/me 读取当前用户', async () => {
    vi.mocked(login).mockResolvedValue({
      accessToken: 'jwt-token',
      tokenType: 'Bearer',
      expiresInSeconds: 3600,
    });
    vi.mocked(fetchMe).mockResolvedValue({ id: 7, username: 'alice', role: 'USER' });

    renderApp();
    await fillCredentials('alice', 'Passw0rd!23');
    await userEvent.click(screen.getByRole('button', { name: '登录' }));

    expect(login).toHaveBeenCalledWith('alice', 'Passw0rd!23');
    expect(await screen.findByText('alice')).toBeInTheDocument();
    expect(screen.getByText('USER')).toBeInTheDocument();
    expect(fetchMe).toHaveBeenCalledTimes(1);
    expect(getToken()).toBe('jwt-token');
  });

  it('注册成功后自动登录并进入当前用户页', async () => {
    vi.mocked(register).mockResolvedValue({ id: 9, username: 'bob', role: 'USER' });
    vi.mocked(login).mockResolvedValue({
      accessToken: 'bob-token',
      tokenType: 'Bearer',
      expiresInSeconds: 3600,
    });
    vi.mocked(fetchMe).mockResolvedValue({ id: 9, username: 'bob', role: 'USER' });

    renderApp();
    await fillCredentials('bob', 'Passw0rd!23');
    await userEvent.click(screen.getByRole('button', { name: '注册并登录' }));

    expect(register).toHaveBeenCalledWith('bob', 'Passw0rd!23');
    expect(await screen.findByText('bob')).toBeInTheDocument();
    expect(getToken()).toBe('bob-token');
  });

  it('密码错误时显示通用提示且不保存 token', async () => {
    vi.mocked(login).mockRejectedValue(
      new ApiError(401, 'INVALID_CREDENTIALS', 'username or password is incorrect'),
    );

    renderApp();
    await fillCredentials('alice', 'wrong-password');
    await userEvent.click(screen.getByRole('button', { name: '登录' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('用户名或密码错误');
    expect(getToken()).toBeNull();
    expect(fetchMe).not.toHaveBeenCalled();
  });

  it('登录成功但 /users/me 失败时清除 token 并保持未登录', async () => {
    vi.mocked(login).mockResolvedValue({
      accessToken: 'jwt-token',
      tokenType: 'Bearer',
      expiresInSeconds: 3600,
    });
    vi.mocked(fetchMe).mockRejectedValue(new ApiError(500, 'INTERNAL_ERROR', 'boom'));

    renderApp();
    await fillCredentials('alice', 'Passw0rd!23');
    await userEvent.click(screen.getByRole('button', { name: '登录' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('请求失败，请稍后重试');
    expect(fetchMe).toHaveBeenCalledTimes(1);
    expect(getToken()).toBeNull();
    expect(screen.queryByText('当前用户')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: '登录' })).toBeInTheDocument();
  });

  it('注册成功但 /users/me 失败时同样清除 token', async () => {
    vi.mocked(register).mockResolvedValue({ id: 9, username: 'bob', role: 'USER' });
    vi.mocked(login).mockResolvedValue({
      accessToken: 'bob-token',
      tokenType: 'Bearer',
      expiresInSeconds: 3600,
    });
    vi.mocked(fetchMe).mockRejectedValue(new ApiError(401, 'UNAUTHORIZED', 'expired'));

    renderApp();
    await fillCredentials('bob', 'Passw0rd!23');
    await userEvent.click(screen.getByRole('button', { name: '注册并登录' }));

    expect(await screen.findByRole('alert')).toBeInTheDocument();
    expect(getToken()).toBeNull();
  });

  it('刷新时 /users/me 失败会丢弃陈旧 token 并回到登录页', async () => {
    setToken('stale-token');
    vi.mocked(fetchMe).mockRejectedValue(new ApiError(401, 'UNAUTHORIZED', 'expired'));

    renderApp('/');

    expect(await screen.findByRole('button', { name: '登录' })).toBeInTheDocument();
    expect(fetchMe).toHaveBeenCalledTimes(1);
    expect(getToken()).toBeNull();
  });
});
