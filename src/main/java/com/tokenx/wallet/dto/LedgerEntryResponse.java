package com.tokenx.wallet.dto;

import com.tokenx.wallet.model.LedgerBizType;
import com.tokenx.wallet.model.LedgerEffectType;
import com.tokenx.wallet.model.LedgerEntryRow;
import java.time.Instant;

/**
 * One wallet posting as a client may see it.
 *
 * <p>Enumerations are rendered by name ({@code ORDER}, {@code CREDIT}) through an explicit lookup,
 * so the numeric codes the table stores stay internal and adding a code later cannot silently change
 * what an existing response means.
 *
 * @param entryId the posting's own id, and the cursor for the next page
 * @param bizType what the posting is about; {@code ORDER} in V1
 * @param orderNo the public order number, {@code null} only if the order row is missing
 * @param effectType {@code CREDIT} or {@code DEBIT}
 * @param delta signed change; positive for a credit
 * @param balanceAfter the balance after this posting
 * @param createdAt when the posting was written
 */
public record LedgerEntryResponse(
    Long entryId,
    String bizType,
    String orderNo,
    String effectType,
    Long delta,
    Long balanceAfter,
    Instant createdAt) {

  public static LedgerEntryResponse from(LedgerEntryRow row) {
    return new LedgerEntryResponse(
        row.getEntryId(),
        LedgerBizType.fromCode(row.getBizType()).name(),
        row.getOrderNo(),
        LedgerEffectType.fromCode(row.getEffectType()).name(),
        row.getDelta(),
        row.getBalanceAfter(),
        row.getCreatedInstant());
  }
}
