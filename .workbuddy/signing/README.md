# 发布签名密钥（本地副本）

⚠️ **本目录下所有私钥material 都已被 .gitignore 排除，绝不要提交。**
#
# ⚠️ 这把密钥是应用内更新的根基：**一旦丢失或更换，已发布的版本就再也无法覆盖升级**，
# 用户必须卸载重装。请务必备份，且不要提交进仓库（*.keystore 已在 .gitignore）。
#
# 与 GitHub Secrets 中的 KEYSTORE_BASE64 是同一把（alias=market-monitor）。
# 证书 SHA-256 指纹（release.yml 里的断言依据）：
#   cf2e20c74d1edd4d1fb290afee3b68ed1a18e20ac70a89e4ab5c43ec2d52f034
#
# 用法：本目录下的 release.keystore，配合仓库根的 keystore.properties。
