import { Link, Navigate } from 'react-router-dom';
import { useAuth } from '../auth/useAuth';

export function HomePage() {
  const { user, initializing, signOut } = useAuth();

  if (initializing) {
    return <main className="card">正在加载当前用户…</main>;
  }
  if (!user) {
    return <Navigate to="/login" replace />;
  }

  return (
    <main className="card">
      <h1>当前用户</h1>
      <dl>
        <dt>用户 ID</dt>
        <dd>{user.id}</dd>
        <dt>用户名</dt>
        <dd>{user.username}</dd>
        <dt>角色</dt>
        <dd>{user.role}</dd>
      </dl>
      <button type="button" onClick={signOut}>
        退出登录
      </button>
      <p className="hint">
        <Link to="/products">查看 Token 套餐</Link>
      </p>
    </main>
  );
}
