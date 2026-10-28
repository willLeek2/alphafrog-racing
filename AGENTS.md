# AlphaFrog Racing

- 通用协作与 Java 开发规则遵循 AlphaFrog 主仓库的 `AGENTS.md`。
- 使用 JDK 17 和仓库内的 `./mvnw`，执行不跳过测试的 `package` 后交付。
- `racing-api` 保存 RPC 契约，`racing-server` 提供服务；调用方依赖接口模块。
- 注册中心地址、注册组和泳道身份由运行环境配置，凭据不得进入仓库。
- 本机验证不启动 Docker、PostgreSQL 或 Redis；容器验证由获准的 Beta 入口执行。
