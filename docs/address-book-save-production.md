# 通讯录字段校验和转明文

当前本地示例为 `SensitiveDataDemoServiceImpl.addressBookText` 和 `editableText`，复用已有 reveal 服务查询 Redis 并解密。
下面的 `restoreContact` 表示需接入生产 service 的还原函数，并非仓库中已提供的方法；完整生产 service 不在当前快照中，不能据此认定旁路已经接入。
在原 `validAddressFiled` 循环的注释处调用：

```java
// 三个敏感字段的格式校验和转明文
 data.setEmail(restoreContact(data.getEmail(), Constant.REGEX_EMAIL));
 data.setTelNumber(restoreContact(data.getTelNumber(), Constant.REGEX_TELEPHONE));
 data.setMobileNumber(restoreContact(data.getMobileNumber(), Constant.REGEX_MOBILE));
```

明文直接校验，完整脱敏标记提取 token 还原后校验，结果回写字段。
Redis 过期或还原失败直接抛出异常。后续沿用原加密保存流程。

写入绝对基线见 `sensitive-write-baseline.md`：还原失败必须在原手工加密前抛错，原加密失败或返回无效结果也必须中止保存。不得以关闭展示脱敏开关为由跳过原加密，亦不得把手工加密结果再次交给通用拦截器加密。
