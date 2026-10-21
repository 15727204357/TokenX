package com.tokenx.wallet.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tokenx.wallet.model.LedgerEntryRow;
import com.tokenx.wallet.model.TokenLedger;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * Access to {@code token_ledger}.
 *
 * <p>The application only ever inserts here — a posting is evidence, so no update or delete exists
 * on purpose. Reads are a single user-scoped, cursor-based walk.
 */
@Mapper
public interface TokenLedgerMapper extends BaseMapper<TokenLedger> {

  /**
   * One page of a user's postings, newest first, after an id cursor.
   *
   * <p>{@code user_id} is a parameter of the statement, never a filter applied afterwards: a page
   * that is fetched and then narrowed in Java would still have read someone else's rows.
   *
   * <p>{@code limit + 1} rows are asked for so the caller can tell whether another page exists
   * without a second query and without a {@code COUNT(*)}.
   *
   * <p>{@code biz_id} is turned into the public order number by the join, so the internal order id
   * never leaves the server. The join is outer on purpose: orders are never deleted, so a posting
   * whose order is missing means corruption — and hiding that posting would also hide a balance
   * difference, which is worse than showing one entry without a number.
   *
   * <p>The join also requires the order to belong to the same person as the posting ({@code
   * o.user_id = l.user_id}). Without that predicate a single corrupted {@code biz_id} would answer
   * one user's wallet page with another user's order number — there is no foreign key to prevent the
   * bad row in the first place, so the read is where the two identities have to agree. A mismatch is
   * not filtered out: the posting stays in the page, still carrying its own amounts and timestamp,
   * but {@code order_no} comes back {@code null}, which is exactly what a missing order produces.
   * Suppressing the row instead would hide a balance difference along with the leak.
   *
   * @param userId authenticated owner; the only account whose rows can be returned
   * @param cursor last {@code id} of the previous page, {@code null} for the first page
   * @param limit page size plus one, already bounded by the service
   */
  @Select(
      """
      <script>
      SELECT l.id AS entry_id,
             l.biz_type,
             l.effect_type,
             l.delta,
             l.balance_after,
             l.created_at,
             o.order_no
        FROM token_ledger l
        LEFT JOIN purchase_order o
               ON o.id = l.biz_id
              AND o.user_id = l.user_id
       WHERE l.user_id = #{userId}
         AND l.biz_type = 1
      <if test="cursor != null">
         AND l.id &lt; #{cursor}
      </if>
       ORDER BY l.id DESC
       LIMIT #{limit}
      </script>
      """)
  List<LedgerEntryRow> selectPageByUser(
      @Param("userId") long userId, @Param("cursor") Long cursor, @Param("limit") int limit);
}
