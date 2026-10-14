# Maven 源码包手动发布与验证

历史发布记录：`1.3.5` 已发布完成，Central 和阿里云都已下载验证，SHA256 一致。
[下载 1.3.5 阿里云源码包](https://maven.aliyun.com/repository/central/io/github/simon-jszl/union-control-source/1.3.5/union-control-source-1.3.5-source.zip)。这不是当前最新版本声明。

namespace、GPG 密钥和公钥上传已在本机配置好，不需要每次重做。
下面步骤在同一个终端中依次执行；任何检查失败时先处理错误，再继续下一步。

## 1. 更新版本

修改以下两个文件，保持版本一致，选择尚未发布的新版本：

- [package.json](../package.json)
- [publishing/pom.xml](pom.xml)

同时更新 [发布说明](README.md) 中的下载版本。Central 已发布版本不能覆盖。

## 2. 检查并打包

在仓库根目录执行：

```bash
cd /Users/simon/code/union-control

PYTHONDONTWRITEBYTECODE=1 python3 publishing/test_build.py
python3 publishing/build.py
```

脚本按 Git 标准忽略规则选择整个仓库中的文件，不另设目录白名单或文件名黑名单。
未被忽略的新文件也会打包；已删除文件和命中忽略规则的文件不会打包。
`schema.sql`、本地配置、文档等是否纳入，只由 Git 忽略规则决定。

设置本次版本，后面命令继续使用：

```bash
VERSION=$(python3 -c 'import json; print(json.load(open("package.json"))["version"])')
RELEASE="$PWD/target/maven-central/$VERSION"
ARTIFACT="$RELEASE/repository/io/github/simon-jszl/union-control-source/$VERSION"
```

检查 ZIP 内容，尤其是本次更新的文件：

```bash
unzip -l "$ARTIFACT/union-control-source-$VERSION-source.zip"
```

## 3. 签名

使用已经配置好的本机密钥：

```bash
KEY=$(cat "$HOME/.config/union-control-publish/fingerprint")

for FILE in "$ARTIFACT"/*.pom "$ARTIFACT"/*.zip; do
  /opt/homebrew/bin/gpg \
    --batch --yes \
    --pinentry-mode loopback \
    --passphrase-file "$HOME/.config/union-control-publish/gpg-passphrase" \
    --local-user "$KEY" \
    --armor --detach-sign "$FILE"

  /opt/homebrew/bin/gpg --verify "$FILE.asc" "$FILE"
done
```

两份文件都应显示 `Good signature`。构建脚本已经生成校验和；如果重新打包，必须重新签名。

## 4. 生成上传用的 bundle

```bash
(
  cd "$RELEASE/repository"
  zip -r ../central-bundle.zip io
)
```

上传的是：

```text
target/maven-central/版本号/central-bundle.zip
```

**不要只上传 `-source.zip`**：bundle 才包含 Central 要求的目录结构、POM、签名和校验和。

## 5. 在 Central 网页发布

打开 [Central 发布页面](https://central.sonatype.com/publishing)：

1. 点击 **Publish Component**。
2. 上传 `central-bundle.zip`。
3. 等待状态变为 **VALIDATED**。
4. 点击 **Publish**。
5. 等待 **PUBLISHED**。

用网页操作无需填写本地的 token 文件；token 是脚本调用发布 API 时使用的。

## 6. 下载并验证

仍在刚才的终端执行：

```bash
ARTIFACT_PATH="io/github/simon-jszl/union-control-source/$VERSION/union-control-source-$VERSION-source.zip"

curl -fL \
  "https://repo.maven.apache.org/maven2/$ARTIFACT_PATH" \
  -o "$RELEASE/central-download.zip"

curl -fL \
  "https://maven.aliyun.com/repository/central/$ARTIFACT_PATH" \
  -o "$RELEASE/aliyun-download.zip"
```

确认两次下载都成功后再比较校验和：

```bash
shasum -a 256 \
  "$ARTIFACT/union-control-source-$VERSION-source.zip" \
  "$RELEASE/central-download.zip" \
  "$RELEASE/aliyun-download.zip"
```

**三个 SHA256 应完全相同。** 阿里云暂时返回 404 时，等待镜像更新后重试，不必重新发布。
下载失败时先不要执行 `shasum`，否则可能出现文件不存在，或误用此前留下的下载文件。

内网验证时，把下载地址前面的 `https://maven.aliyun.com/repository/central`
换成内网 Maven 仓库地址，后面的 `$ARTIFACT_PATH` 保持不变。
阿里云可下载不代表内网仓库已经刷新；内网还需单独下载并核对 SHA256。
