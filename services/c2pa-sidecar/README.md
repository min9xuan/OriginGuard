# C2PA Sidecar

该目录提供受控 C2PA 验证服务。Spring Boot 只把已读取的媒体字节传给固定接口；sidecar 使用参数数组调用官方 `c2patool`，不会拼接或执行任意 Shell 命令。

- `POST /v1/verify`
- `GET /health`

运行 `scripts/setup-c2pa.ps1` 可把固定版本的官方 Windows 二进制安装到 `.runtime/tools/c2patool`。未安装时服务仍可启动，并明确返回 `NOT_CONFIGURED`；媒体没有凭证时返回 `NOT_FOUND`，不会把“无凭证”误判为异常或伪造。

C2PA 结果只验证内容凭证、签名/绑定状态和声明的编辑历史，不直接判断画面是否真实或由 AI 生成。
