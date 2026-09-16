package com.union.control.service;

import java.util.List;
import java.util.Map;

public interface SensitiveDataDemoService {
    int insert(String input);
    Map<String, Object> save(String input);
    Map<String, Object> saveAddressBook(String input);
    List<Map<String, Object>> query(String input);
    int insertAddressBook(String input);
    List<Map<String, Object>> queryAddressBook(String input);
}
