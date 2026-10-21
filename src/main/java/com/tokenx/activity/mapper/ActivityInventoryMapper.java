package com.tokenx.activity.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tokenx.activity.model.ActivityInventory;
import org.apache.ibatis.annotations.Mapper;

/**
 * Access to {@code activity_inventory}.
 *
 * <p>F3 only inserts the initial row. Conditional stock updates ({@code WHERE available_stock > 0})
 * belong to F4 and will be explicit SQL here, never a generic update.
 */
@Mapper
public interface ActivityInventoryMapper extends BaseMapper<ActivityInventory> {}
