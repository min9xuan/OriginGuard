# Architecture

OriginGuard 采用 Monorepo、前后端分离和模块化单体。Java 服务拥有业务状态与事务；Python 服务只承担模型推理；外部能力通过 Port/Adapter 接入。

核心依赖方向：`interfaces -> application -> domain`，`infrastructure` 实现 application/domain 声明的端口。模块之间通过应用服务或领域事件协作，不直接访问其他模块的 Mapper。

