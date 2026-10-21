package com.tokenx.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tokenx.auth.model.TokenAccount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * Data access for {@code token_account}.
 *
 * <p>F1 inserts the zero-balance row in the registration transaction. F5 adds the one statement that
 * can change a balance — an addition performed by the database, not by the application.
 *
 * <p>The row is created exactly once and there is no second mapper for this table: two places that
 * know how a balance changes would be two answers to the same question, which is the one thing the
 * ledger exists to prevent. The wallet feature therefore reads and writes the account through this
 * mapper instead of growing its own copy of the entity.
 */
@Mapper
public interface TokenAccountMapper extends BaseMapper<TokenAccount> {

  /**
   * Adds credits to one account, atomically, and reports whether the account existed.
   *
   * <p>{@code available_balance = available_balance + ?} is evaluated by the database, so two
   * concurrent payments of two different orders by the same user both land — a read-modify-write in
   * Java would let one overwrite the other.
   *
   * <p>An affected-row count of {@code 0} is not "nothing to do": registration guarantees one row
   * per user, so the caller must treat it as a broken invariant and roll the whole payment back.
   * Creating the row here is explicitly not allowed — a payment is the wrong place to discover that
   * a user has no account, and a lazy insert would race with every other payment of that user.
   *
   * <p>Adding to a {@code BIGINT UNSIGNED} column that would overflow raises a database error, so an
   * unreasonable balance fails the transaction instead of wrapping around to a small number.
   */
  @Update(
      """
      UPDATE token_account
         SET available_balance = available_balance + #{delta}
       WHERE user_id = #{userId}
      """)
  int creditBalance(@Param("userId") long userId, @Param("delta") long delta);
}
