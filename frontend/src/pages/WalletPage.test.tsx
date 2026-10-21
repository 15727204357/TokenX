import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fetchMe } from '../api/auth';
import { ApiError } from '../api/http';
import { fetchLedger, fetchWallet, type LedgerEntry, type LedgerPage } from '../api/wallet';
import { AuthProvider } from '../auth/AuthContext';
import { WalletPage } from './WalletPage';

vi.mock('../api/auth');
vi.mock('../api/wallet');

const firstEntry: LedgerEntry = {
  entryId: 30,
  bizType: 'ORDER',
  orderNo: 'ABCDEFGHJK0000000000000001',
  effectType: 'CREDIT',
  delta: 600,
  balanceAfter: 600,
  createdAt: '2026-10-21T12:05:00Z',
};

const SECOND_ORDER_NO = 'ABCDEFGHJK0000000000000002';

const secondEntry: LedgerEntry = {
  entryId: 20,
  bizType: 'ORDER',
  orderNo: SECOND_ORDER_NO,
  effectType: 'CREDIT',
  delta: 400,
  balanceAfter: 1000,
  createdAt: '2026-10-21T12:10:00Z',
};

function page(items: LedgerEntry[], nextCursor: number | null): LedgerPage {
  return { items, nextCursor, hasMore: nextCursor !== null };
}

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/wallet']}>
      <AuthProvider>
        <Routes>
          <Route path="/wallet" element={<WalletPage />} />
          <Route path="/login" element={<p>登录页标记</p>} />
        </Routes>
      </AuthProvider>
    </MemoryRouter>,
  );
}

/**
 * Puts the provider in a logged-in state: a stored token plus a confirmed /users/me.
 *
 * <p>`window.localStorage` is the same jsdom storage `auth/storage.ts` reads; the bare identifier is
 * Node 26's own stub. See `src/test/setup.ts`.
 */
function signInAsBuyer() {
  window.localStorage.setItem('tokenx.accessToken', 'stored-token');
  vi.mocked(fetchMe).mockResolvedValue({ id: 42, username: 'buyer', role: 'USER' });
}

describe('钱包页', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    window.localStorage.clear();
  });

  it('展示服务端余额与最新流水', async () => {
    signInAsBuyer();
    vi.mocked(fetchWallet).mockResolvedValue({
      availableBalance: 1000,
      updatedAt: '2026-10-21T12:11:00Z',
    });
    vi.mocked(fetchLedger).mockResolvedValue(page([secondEntry, firstEntry], null));

    renderPage();

    expect(await screen.findByText('1000 Token')).toBeInTheDocument();
    expect(screen.getByText('2026-10-21 20:11:00')).toBeInTheDocument();
    expect(screen.getByText('2026-10-21 20:10:00')).toBeInTheDocument();
    expect(screen.getByText('+400')).toBeInTheDocument();
    expect(screen.getByText('余额 1000')).toBeInTheDocument();
    expect(screen.getByText('余额 600')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: SECOND_ORDER_NO })).toBeInTheDocument();
    expect(fetchLedger).toHaveBeenCalledTimes(1);
    expect(vi.mocked(fetchLedger).mock.calls[0]).toEqual([]);
  });

  it('按服务端 nextCursor 加载下一页并追加，没有更多时按钮消失', async () => {
    signInAsBuyer();
    vi.mocked(fetchWallet).mockResolvedValue({
      availableBalance: 1000,
      updatedAt: '2026-10-21T12:10:00Z',
    });
    vi.mocked(fetchLedger)
      .mockResolvedValueOnce(page([secondEntry], secondEntry.entryId))
      .mockResolvedValueOnce(page([firstEntry], null));

    renderPage();

    const more = await screen.findByRole('button', { name: '加载更多' });
    await userEvent.click(more);

    expect(await screen.findByText('余额 600')).toBeInTheDocument();
    expect(vi.mocked(fetchLedger).mock.calls[1]).toEqual([secondEntry.entryId]);
    expect(screen.queryByRole('button', { name: '加载更多' })).not.toBeInTheDocument();
    // The already visible page was not replaced.
    expect(screen.getByText('余额 1000')).toBeInTheDocument();
  });

  it('加载更多失败时提示并可再次点击', async () => {
    signInAsBuyer();
    vi.mocked(fetchWallet).mockResolvedValue({
      availableBalance: 1000,
      updatedAt: '2026-10-21T12:10:00Z',
    });
    vi.mocked(fetchLedger)
      .mockResolvedValueOnce(page([secondEntry], secondEntry.entryId))
      .mockRejectedValueOnce(new ApiError(500, 'INTERNAL_ERROR', 'unexpected server error'))
      .mockResolvedValueOnce(page([firstEntry], null));

    renderPage();

    await userEvent.click(await screen.findByRole('button', { name: '加载更多' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('加载更多流水失败，请稍后重试');
    expect(screen.getByText('余额 1000')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: '加载更多' }));

    expect(await screen.findByText('余额 600')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('首次加载失败时显示错误并可重试', async () => {
    signInAsBuyer();
    vi.mocked(fetchWallet)
      .mockRejectedValueOnce(new ApiError(500, 'INTERNAL_ERROR', 'unexpected server error'))
      .mockResolvedValueOnce({ availableBalance: 1000, updatedAt: '2026-10-21T12:10:00Z' });
    vi.mocked(fetchLedger).mockResolvedValue(page([firstEntry], null));

    renderPage();

    expect(await screen.findByRole('alert')).toHaveTextContent('钱包加载失败，请稍后重试');

    await userEvent.click(screen.getByRole('button', { name: '重试' }));

    expect(await screen.findByText('1000 Token')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('没有流水时说明暂时没有入账', async () => {
    signInAsBuyer();
    vi.mocked(fetchWallet).mockResolvedValue({
      availableBalance: 0,
      updatedAt: '2026-10-21T12:10:00Z',
    });
    vi.mocked(fetchLedger).mockResolvedValue(page([], null));

    renderPage();

    expect(await screen.findByText('0 Token')).toBeInTheDocument();
    expect(screen.getByText('暂无入账记录')).toBeInTheDocument();
    expect(fetchLedger).toHaveBeenCalledTimes(1);
  });

  it('未登录直接跳转登录页，不请求钱包', async () => {
    renderPage();

    expect(await screen.findByText('登录页标记')).toBeInTheDocument();
    expect(fetchWallet).not.toHaveBeenCalled();
    expect(fetchLedger).not.toHaveBeenCalled();
  });
});
