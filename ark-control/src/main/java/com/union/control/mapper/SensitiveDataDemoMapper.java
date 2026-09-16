package com.union.control.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

@Mapper
public interface SensitiveDataDemoMapper {
    int insert(@Param("phoneNumber") String phoneNumber);

    List<Map<String, Object>> query();

    int insertSaved(Map<String, Object> row);
    int update(Map<String, Object> row);
    Map<String, Object> queryById(@Param("id") long id);

    int insertAddressBook(SensitiveAddressBookDemo row);

    List<SensitiveAddressBookDemo> queryAddressBook();
    int updateAddressBook(SensitiveAddressBookDemo row);
    SensitiveAddressBookDemo queryAddressBookById(@Param("id") long id);
}
