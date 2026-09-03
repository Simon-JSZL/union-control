package com.union.control.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

@Mapper
public interface SensitiveDataDemoMapper {
    int insert(@Param("phoneNumber") String phoneNumber);

    List<Map<String, Object>> query();

    int insertAddressBook(SensitiveAddressBookDemo row);

    List<SensitiveAddressBookDemo> queryAddressBook();
}
