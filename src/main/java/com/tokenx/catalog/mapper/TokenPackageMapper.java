package com.tokenx.catalog.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tokenx.catalog.model.TokenPackage;
import org.apache.ibatis.annotations.Mapper;

/** CRUD access to {@code token_package}. Status filtering lives in the service, not here. */
@Mapper
public interface TokenPackageMapper extends BaseMapper<TokenPackage> {}
