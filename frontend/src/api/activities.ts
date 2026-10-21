import { http } from './http';

/** Run-time state computed by the server from its clock; the browser never derives it. */
export type ActivityRuntimeStatus = 'UPCOMING' | 'ACTIVE' | 'ENDED';

/**
 * Public activity detail.
 *
 * <p>The server sends only published activities here, and it already decided `runtimeStatus` and
 * `soldOut`, so the page can render them without comparing timestamps locally.
 */
export interface ActivityDetail {
  id: number;
  packageId: number;
  activityPriceCent: number;
  creditAmount: number;
  startAt: string;
  endAt: string;
  runtimeStatus: ActivityRuntimeStatus;
  totalStock: number;
  availableStock: number;
  soldOut: boolean;
  purchaseLimit: number;
}

/** Reads one activity. A draft, a withdrawn activity and an unknown id all answer 404. */
export async function fetchActivity(id: string | number): Promise<ActivityDetail> {
  const { data } = await http.get<ActivityDetail>(`/activities/${id}`);
  return data;
}
