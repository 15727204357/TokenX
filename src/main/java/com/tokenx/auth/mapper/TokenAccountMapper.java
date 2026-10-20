package com.tokenx.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tokenx.auth.model.TokenAccount;
import org.apache.ibatis.annotations.Mapper;

/** Data access for {@code token_account}. F1 only inserts the zero-balance row. */
@Mapper
public interface TokenAccountMapper extends BaseMapper<TokenAccount> {}
