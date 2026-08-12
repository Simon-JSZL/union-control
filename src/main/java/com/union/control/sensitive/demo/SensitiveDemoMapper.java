package com.union.control.sensitive.demo;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface SensitiveDemoMapper {
    int insert(SensitiveDemoRecord record);
    List<SensitiveDemoRecord> list(@Param("userId") String userId);
}
