# C2PA Sidecar

该目录预留受控 C2PA 验证服务。Spring Boot 只能通过固定接口传入对象键，不能拼接或执行任意 Shell 命令。

阶段 2 将实现：

- `POST /v1/verify`
- `POST /v1/read-manifest`
- `GET /health`

在确认 `c2patool` 的安装位置、许可证和下载目录前，本阶段不下载二进制文件。

