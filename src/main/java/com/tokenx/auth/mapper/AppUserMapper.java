package com.tokenx.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tokenx.auth.model.AppUser;
import org.apache.ibatis.annotations.Mapper;

/**
 * Data access for {@code app_user}.
 *
 * <p>Registration relies on the unique index rather than a pre-check query: a duplicate username is
 * surfaced as a {@link org.springframework.dao.DuplicateKeyException} and translated by the service.
 */
@Mapper
public interface AppUserMapper extends BaseMapper<AppUser> {}
