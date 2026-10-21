import { http } from './http';

export type PackageStatus = 'ON_SALE' | 'OFF_SHELF';

/** Package as returned by the public catalogue. Amounts are integer cents / integer credits. */
export interface Product {
  id: number;
  name: string;
  creditAmount: number;
  listPriceCent: number;
  status: PackageStatus;
}

/** One cursor page; `nextCursor` is `null` exactly when there is nothing more to fetch. */
export interface ProductPage {
  items: Product[];
  nextCursor: number | null;
  hasMore: boolean;
}

/**
 * Reads one page of on-sale packages.
 *
 * @param cursor id returned as `nextCursor` by the previous page; omit for the first page
 * @param limit page size the server caps at 100
 */
export async function fetchProducts(cursor?: number | null, limit = 20): Promise<ProductPage> {
  const params: Record<string, number> = { limit };
  if (cursor !== undefined && cursor !== null) {
    params.cursor = cursor;
  }
  const { data } = await http.get<ProductPage>('/products', { params });
  return data;
}
