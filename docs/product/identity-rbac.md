# 身份、RBAC 与租户隔离基础

## 当前实现

- 登录必须同时提供租户代码、用户名和密码。
- 密码使用 BCrypt（cost 12）保存。
- Access Token 为 15 分钟 JWT，包含用户、租户、角色和有效权限。
- Refresh Token 为 256 位随机值，浏览器只通过 HttpOnly、SameSite=Strict Cookie 持有；数据库只保存 SHA-256 Hash。
- 刷新时旧 Token 立即撤销并轮换新 Token；退出时撤销当前 Refresh Token。
- 后端使用方法级权限校验，前端隐藏菜单不作为安全边界。
- `CurrentActorProvider` 从已验证 JWT 构建操作者上下文；`TenantAccessPolicy` 拒绝跨租户资源访问。

## 角色边界

### INVESTIGATOR

上传和读取媒体、创建和推进案件、运行 Agent、查看 Trace、提交审核。不能审核或签署报告。

### REVIEWER

查看分配的复核任务，接受或驳回证据，编辑、签署报告并归档确认案件。不能管理用户、模型或工具。

### ADMIN

管理用户、角色、知识、模型、工具和系统审计。可以只读查看案件和报告，但默认没有：

```text
review:approve
review:reject
report:edit
report:finalize
```

如果管理员本人需要承担审核工作，必须额外分配 `REVIEWER`，且资源级策略仍禁止自审。

## 数据表

```text
tenant
sys_user
sys_role
sys_permission
sys_user_role
sys_role_permission
auth_refresh_token
```

数据库结构由 `V1__identity_rbac.sql` 管理。本地 profile 会幂等同步三个内置角色及开发账号；正式环境必须关闭 Bootstrap 并使用独立密钥、HTTPS 和安全 Cookie。

## 已覆盖测试

- 未登录访问返回结构化 401；
- 错误密码不暴露具体失败字段；
- 调查员不能调用管理员接口；
- 管理员具备用户管理权限但不具备审核与签署权限；
- 管理员统计只包含当前租户；
- 审核员具备复核和签署权限；
- Refresh Token 轮换、旧 Token 失效、退出撤销。

## 相关设计文档

- [当前项目设计计划书](./OriginGuard_项目设计计划书.md)
- [角色与权限规划](./OriginGuard_角色与权限规划.md)
- [ADR-006：分离系统管理与案件审核职责](../adr/ADR-006-separate-administration-from-review.md)
