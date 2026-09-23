# Sensitive 写入安全基线检查

2026-09-22。最高优先级是数据完整性：展示可报错，也可通过配置展示明文；写入不得因任何展示降级而跳过还原、校验和既有加密。不能保证正确写入时，中止该次 SQL。

## 本次发现并修复

| 风险 | 处理 |
| --- | --- |
| `interceptorItems` 关闭后写入直接放行 | 已知 ENCRYPT statement 始终执行加密；展示配置不参与写入决策 |
| 关闭旧拦截配置后读取裸返数据库密文，编辑提交二次加密 | 已知 DECRYPT statement 始终处理密文；非严格模式下配置可选择明文展示 |
| `ParamMap/list/collection/param1` 包装导致批量或命名参数未加密 | 遍历 Map、数组、Iterable，按对象身份去重，处理原注解及已知敏感 key |
| 普通 Map 中共享同一 String 对象的无关字段被一起加密 | 只修改敏感 key；仅对真实 MyBatis ParamMap 的通用别名同步；无法判断的别名冲突拒绝执行 |
| 脱敏标记被当明文加密，或 token 内容与显示值不符 | 恢复 token 对应片段并核对生成的 mask，之后执行原存储 codec；残留可识别脱敏标记阻止 SQL |
| 后续字段失败后，前面字段留下密文；同对象重试被二次加密 | 所有转换成功后才应用；应用失败回滚已修改字段；Executor 返回或抛错后恢复输入，保留 generated keys |
| 加密服务返回成功但 payload/content 为空 | 抛错，拒绝以 null/空字符串覆盖非空字段；Processor 同时拒绝明显无效的加密结果 |
| `encryptWithTag` 重新扫描生成的密文 | 只匹配原文一次，避免密文偶然符合手机号正则而被再次替换；存储标签格式保持不变 |
| 长文本分段切断 Unicode 代理对，UTF-8 编码后变为 `??` | 分段边界保留完整代理对；原分隔符和解密格式不变；非法负分段长度拒绝 |

普通明文中的 `[#`、`#VIEW:说明`、`****` 不因此被拒绝。保留实际生成的脱敏协议识别，不以任意前缀或星号判断。完整 marker 的 token 无效、过期、Redis 异常、解密异常、内容与 mask 不一致均阻止写入；`[#138****5678]`、`[#****]` 等无 token 的系统展示值也不能保存。

## 验证

Java 8；从仓库根目录运行：

```sh
mvn -pl ark-control -am -Plocal-sensitive-compat,sensitive-tests test
```

实测 `clean test`：65 项测试，0 失败、0 错误、0 跳过。

此 profile 现在明确编译 `ark-control/import-test/sensitive`，不会误用被忽略的本地旧测试目录。覆盖 Processor、Interceptor、AddressBook、Redis/token、crypto codec，以及真实 MyBatis SIMPLE/REUSE/BATCH Executor 和 JDBC 参数绑定。

JDBC 测试使用 JCE AES256 测试代理和 mock JDBC，不连接生产数据库。验证实际绑定的密文可解密回原文、关闭展示拦截配置仍加密、Redis 不可用时纯明文新写不受影响、token 失败时 JDBC 零调用、查询脱敏后原样提交、批量 foreach/重复别名/重复对象、重复保存及 generated key。

MyBatis 3.4.6 官方源码 `BatchExecutor.doUpdate` 会先 parameterize，再 `PreparedStatement.addBatch`；flush 时执行 batch 并处理 generated keys。临时恢复策略已按该实现和三类真实执行器验证。

## 生产接入边界与放行条件

- 当前生产快照缺完整业务 Mapper、Control root/effective POM、实际数据库及真实加密服务。不能宣称已证明所有生产 SQL 的安全性；接入前必须核对 ENCRYPT/DECRYPT 清单、注解、Map key、参数包装、TypeHandler、插件顺序和实际依赖版本。未命中当前清单的 SQL 仍按原系统处理，不能把它们计入本次覆盖范围。
- 生产通讯录仍走原手工加密及 MyBatis-Plus `insertOrUpdateBatch`。在原校验/加密之前还原完整 token；失败抛错。禁止把已经手工加密的写入再交给公共加密拦截器。此快照没有该生产 service，因此该旁路尚未做真实集成验证。
- 展示开关的本地实现仍是生产配置接入位置，严格模式当前有固定 mock 值。允许通过生产配置关闭脱敏、展示已解密明文；本次不引入隐式的 Redis 故障自动明文降级。
- 本地 `sensitiveProxy` 是模拟实现，不能部署为生产密码服务。生产保留其真实密码服务；需同步本次 crypto 工具中的空结果拒绝、原文单次扫描及 Unicode 分段修复，不能只替换拦截器。
- 本次不修改数据库、不发布、不修复历史已污染记录。历史脱敏值或二次密文不能靠启发式自动恢复，应依据可信备份或原始来源恢复。回退应关闭展示功能或暂停相关写入，不能恢复“异常后放行 SQL”的行为。

结论：仓库内已发现的写入漏洞已修复并有回归覆盖；生产全量放行仍取决于上述真实接入检查，不能用本地测试替代。
