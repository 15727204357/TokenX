import { beforeEach, describe, expect, it, vi } from 'vitest';
import { http } from './http';
import { fetchLedger, fetchWallet, type LedgerPage, type WalletBalance } from './wallet';

vi.mock('./http', () => ({
  http: { get: vi.fn(), post: vi.fn() },
}));

const balance: WalletBalance = {
  availableBalance: 1600,
  updatedAt: '2026-10-21T12:10:00Z',
};

const ledgerPage: LedgerPage = {
  items: [
    {
      entryId: 12,
      bizType: 'ORDER',
      orderNo: 'ABCDEFGHJK0000000000000001',
      effectType: 'CREDIT',
      delta: 1600,
      balanceAfter: 1600,
      createdAt: '2026-10-21T12:10:00Z',
    },
  ],
  nextCursor: null,
  hasMore: false,
};

describe('钱包接口', () => {
  beforeEach(() => {
    vi.resetAllMocks();
  });

  it('余额只按当前登录用户请求，不带任何用户标识', async () => {
    vi.mocked(http.get).mockResolvedValue({ data: balance });

    const wallet = await fetchWallet();

    expect(wallet).toEqual(balance);
    expect(http.get).toHaveBeenCalledWith('/wallet');
  });

  it('首屏流水不带游标', async () => {
    vi.mocked(http.get).mockResolvedValue({ data: ledgerPage });

    const page = await fetchLedger();

    expect(page).toEqual(ledgerPage);
    expect(http.get).toHaveBeenCalledWith('/wallet/ledger', { params: {} });
  });

  it('翻页时把游标作为查询参数', async () => {
    vi.mocked(http.get).mockResolvedValue({ data: ledgerPage });

    await fetchLedger(12);

    expect(http.get).toHaveBeenCalledWith('/wallet/ledger', { params: { cursor: 12 } });
  });
});
