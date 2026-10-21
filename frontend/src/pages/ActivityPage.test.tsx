import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fetchActivity, type ActivityDetail } from '../api/activities';
import { ApiError } from '../api/http';
import { AuthProvider } from '../auth/AuthContext';
import { ActivityPage } from './ActivityPage';

vi.mock('../api/activities');

const sample: ActivityDetail = {
  id: 7,
  packageId: 3,
  activityPriceCent: 799,
  creditAmount: 1000,
  startAt: '2026-10-22T02:00:00Z',
  endAt: '2026-10-22T03:00:00Z',
  runtimeStatus: 'UPCOMING',
  totalStock: 100,
  availableStock: 100,
  soldOut: false,
  purchaseLimit: 1,
};

function renderPage(id = '7') {
  return render(
    <MemoryRouter initialEntries={[`/activities/${id}`]}>
      <AuthProvider>
        <Routes>
          <Route path="/activities/:id" element={<ActivityPage />} />
        </Routes>
      </AuthProvider>
    </MemoryRouter>,
  );
}

describe('活动详情页', () => {
  beforeEach(() => {
    vi.resetAllMocks();
  });

  it('展示即将开始的活动、价格、额度和 UTC 时间换算后的窗口', async () => {
    vi.mocked(fetchActivity).mockResolvedValue(sample);

    renderPage();

    expect(await screen.findByText('即将开始')).toBeInTheDocument();
    expect(screen.getByText('¥7.99')).toBeInTheDocument();
    expect(screen.getByText('1000 Token')).toBeInTheDocument();
    expect(screen.getByText('2026-10-22 10:00:00')).toBeInTheDocument();
    expect(screen.getByText('2026-10-22 11:00:00')).toBeInTheDocument();
    expect(screen.getByText('100 / 100')).toBeInTheDocument();
    expect(screen.getByText('1 份')).toBeInTheDocument();
    expect(screen.queryByText('已售罄')).not.toBeInTheDocument();
    expect(fetchActivity).toHaveBeenCalledWith('7');
  });

  it('展示进行中的活动', async () => {
    vi.mocked(fetchActivity).mockResolvedValue({
      ...sample,
      runtimeStatus: 'ACTIVE',
      availableStock: 42,
    });

    renderPage();

    expect(await screen.findByText('进行中')).toBeInTheDocument();
    expect(screen.getByText('42 / 100')).toBeInTheDocument();
  });

  it('展示已结束的活动', async () => {
    vi.mocked(fetchActivity).mockResolvedValue({
      ...sample,
      runtimeStatus: 'ENDED',
      availableStock: 0,
      soldOut: true,
    });

    renderPage();

    expect(await screen.findByText('已结束')).toBeInTheDocument();
    expect(screen.getByText('已售罄')).toBeInTheDocument();
  });

  it('库存为零时显示已售罄但仍展示服务端返回的进行中状态', async () => {
    vi.mocked(fetchActivity).mockResolvedValue({
      ...sample,
      runtimeStatus: 'ACTIVE',
      availableStock: 0,
      soldOut: true,
    });

    renderPage();

    expect(await screen.findByText('已售罄')).toBeInTheDocument();
    expect(screen.getByText('进行中')).toBeInTheDocument();
    expect(screen.getByText('0 / 100')).toBeInTheDocument();
  });

  it('活动不存在时显示 404 文案而不是通用错误', async () => {
    vi.mocked(fetchActivity).mockRejectedValue(
      new ApiError(404, 'ACTIVITY_NOT_FOUND', 'activity not found'),
    );

    renderPage('999');

    expect(await screen.findByText('活动不存在或尚未开放')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '重试' })).not.toBeInTheDocument();
  });

  it('加载失败时显示错误并可重试', async () => {
    vi.mocked(fetchActivity)
      .mockRejectedValueOnce(new ApiError(500, 'INTERNAL_ERROR', 'unexpected server error'))
      .mockResolvedValueOnce(sample);

    renderPage();

    expect(await screen.findByRole('alert')).toHaveTextContent('活动加载失败，请稍后重试');

    await userEvent.click(screen.getByRole('button', { name: '重试' }));

    expect(await screen.findByText('即将开始')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('本阶段不提供抢购按钮', async () => {
    vi.mocked(fetchActivity).mockResolvedValue({ ...sample, runtimeStatus: 'ACTIVE' });

    renderPage();

    expect(await screen.findByText('进行中')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /抢购/ })).not.toBeInTheDocument();
    expect(screen.getByText('抢购功能尚未开放，本页仅展示活动信息。')).toBeInTheDocument();
  });
});
