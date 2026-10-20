import { useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { ApiError } from '../api/http';
import { useAuth } from '../auth/useAuth';

type Mode = 'login' | 'register';

const USERNAME_HINT = '3-64 位字母、数字或下划线';
const PASSWORD_HINT = '至少 8 位';

function messageFor(error: unknown): string {
  if (error instanceof ApiError) {
    switch (error.code) {
      case 'INVALID_CREDENTIALS':
        return '用户名或密码错误';
      case 'ACCOUNT_DISABLED':
        return '账号已被禁用';
      case 'USERNAME_ALREADY_EXISTS':
        return '用户名已被注册';
      case 'VALIDATION_FAILED':
        return `请检查输入：${USERNAME_HINT}，密码${PASSWORD_HINT}`;
      default:
        return '请求失败，请稍后重试';
    }
  }
  return '请求失败，请稍后重试';
}

export function LoginPage() {
  const { signIn, signUp } = useAuth();
  const navigate = useNavigate();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(mode: Mode) {
    setError(null);
    setBusy(true);
    try {
      if (mode === 'login') {
        await signIn(username, password);
      } else {
        await signUp(username, password);
      }
      navigate('/', { replace: true });
    } catch (caught) {
      setError(messageFor(caught));
    } finally {
      setBusy(false);
    }
  }

  function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    void submit('login');
  }

  return (
    <main className="card">
      <h1>登录 TokenX</h1>
      <form onSubmit={onSubmit}>
        <label htmlFor="username">用户名</label>
        <input
          id="username"
          name="username"
          autoComplete="username"
          value={username}
          onChange={(event) => setUsername(event.target.value)}
          required
        />
        <label htmlFor="password">密码</label>
        <input
          id="password"
          name="password"
          type="password"
          autoComplete="current-password"
          value={password}
          onChange={(event) => setPassword(event.target.value)}
          required
        />
        {error ? (
          <p className="error" role="alert">
            {error}
          </p>
        ) : null}
        <div className="actions">
          <button type="submit" disabled={busy}>
            登录
          </button>
          <button type="button" disabled={busy} onClick={() => void submit('register')}>
            注册并登录
          </button>
        </div>
      </form>
      <p className="hint">
        用户名 {USERNAME_HINT}，密码{PASSWORD_HINT}。注册成功后会自动登录。
      </p>
    </main>
  );
}
