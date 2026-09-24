package com.trading.tradeapi.mapper;

import com.trading.tradeapi.entity.UserRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface UserMapper {
    UserRecord findById(@Param("userId") Long userId);
}