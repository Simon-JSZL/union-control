# 通讯录字段校验和转明文

直接参考 `SensitiveDataDemoServiceImpl` 的 `restoreContact`，复用已有 reveal 服务查询 Redis 并解密。
在原 `validAddressFiled` 循环的注释处调用：

```java
// 三个敏感字段的格式校验和转明文
 data.setEmail(restoreContact(data.getEmail(), Constant.REGEX_EMAIL));
 data.setTelNumber(restoreContact(data.getTelNumber(), Constant.REGEX_TELEPHONE));
 data.setMobileNumber(restoreContact(data.getMobileNumber(), Constant.REGEX_MOBILE));
```

明文直接校验，完整脱敏标记提取 token 还原后校验，结果回写字段。
Redis 过期或还原失败直接抛出异常。后续沿用原加密保存流程。
