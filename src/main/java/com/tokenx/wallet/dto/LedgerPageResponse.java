package com.tokenx.wallet.dto;

import java.util.List;

/**
 * One page of wallet postings, newest first.
 *
 * <p>Cursor based ({@code id < cursor}) rather than {@code OFFSET}, for the same reason as the
 * package list: walking deeper into a user's history must not get slower, and a page must not shift
 * because a new posting appeared while it was being read. {@code nextCursor} is the id to send back,
 * and is {@code null} exactly when nothing is left, so the client never has to guess.
 *
 * @param items at most {@code limit} postings
 * @param nextCursor id to pass as {@code cursor} for the next page, or {@code null} at the end
 * @param hasMore whether another page exists
 */
public record LedgerPageResponse(
    List<LedgerEntryResponse> items, Long nextCursor, boolean hasMore) {}
