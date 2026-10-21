package com.tokenx.catalog.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tokenx.catalog.model.PackageStatus;
import com.tokenx.catalog.model.TokenPackage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * Access to {@code token_package}.
 *
 * <p>The two explicit statements below exist because the status change must be safe under
 * concurrency: a plain {@code SELECT} would keep reading the transaction's snapshot even after
 * another transaction committed, so the read and the write are paired with a row lock on exactly one
 * primary key instead of relying on generic CRUD.
 */
@Mapper
public interface TokenPackageMapper extends BaseMapper<TokenPackage> {

  /**
   * Reads one package and locks its row until the current transaction ends.
   *
   * <p>The lock is taken on the primary key, so it covers this package only. A concurrent status
   * change of the same package waits here, then reads the committed value instead of a stale
   * snapshot; changes to other packages are unaffected.
   */
  @Select(
      """
      SELECT id, name, credit_amount, list_price_cent, status, created_at, updated_at
      FROM token_package
      WHERE id = #{id}
      FOR UPDATE
      """)
  TokenPackage selectByIdForUpdate(@Param("id") long id);

  /** Writes the status of one row. The caller must already hold its row lock. */
  @Update("UPDATE token_package SET status = #{status} WHERE id = #{id}")
  int updateStatus(@Param("id") long id, @Param("status") PackageStatus status);
}
