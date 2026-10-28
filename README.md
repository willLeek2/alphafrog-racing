# AlphaFrog Racing

实验平台独立仓库，当前按单体应用组织，部署一个服务进程。目前提供最小问候服务，用于验证接口生成、Dubbo Triple 调用、服务注册和 Beta 泳道接入。实验配置、提示词管理和结果存储尚未实现。

`racing-api` 保存 Protobuf 消息与生成的 RPC 接口，`racing-server` 使用 Spring Boot 提供服务。JDK 17、Spring Boot 3.2.3、Dubbo 3.3.2 与 Protobuf 3.25.3 沿用现有 DataFrog 服务的依赖版本。

## 构建

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
./mvnw -B package
```

Maven Wrapper 固定使用 Maven 3.9.9。服务产物为 `racing-server/target/racing-server-0.1.0-SNAPSHOT.jar`。

容器构建在具有容器运行权限的环境执行：

```bash
bash racing-server/docker_build.sh
IMAGE_TAG=racing-server:example bash racing-server/docker_build.sh
```

构建脚本先执行 Maven 打包，再构建指定标签的镜像，代理参数沿用调用环境。

## RPC 接口

服务为 `world.willfrog.racing.idl.HelloService`，方法为 `SayHello`，默认没有 Dubbo 业务分组或显式版本。请求包含 `name`；空白名称返回 `Hello World`，其余名称返回 `Hello World, <name>`。

响应还包含部署编号、流量范围和部署代际，对应 `AF_DEPLOYMENT_ID`、`AF_LANE_TRAFFIC_SCOPE_ID`、`AF_DEPLOYMENT_GENERATION_ID`。这些字段帮助调用方核对处理请求的实例，泳道路由仍由部署与调用环境负责。

调用检查使用生成的 Java 接口，通过 Triple 协议发送 Protobuf 请求：

```bash
(cd test_scripts && bash rpc_smoke.sh --url tri://127.0.0.1:50071 --name Racing)
```

接口、生成方式和 Java 调用方式参照 [Dubbo 的 Protobuf Triple 文档](https://dubbo.apache.org/en/overview/mannual/java-sdk/tasks/protocols/triple/idl/)。

## 配置与验证

默认 RPC 端口为 50071，可通过 `DUBBO_PROTOCOL_PORT` 或 `RACING_RPC_PORT` 覆盖。注册中心地址支持 `DUBBO_REGISTRY_ADDRESS` 或 `RACING_REGISTRY_ADDRESS`；未覆盖时沿用 `NACOS_ADDRESS` 指定的 Nacos。Beta 的注册组、提供者地址及 `dubbo.tag` 使用部署环境提供的标准 Dubbo 配置。

本地仅验证进程与协议时，可以关闭注册中心，不连接外部 Nacos：

```bash
java -jar racing-server/target/racing-server-0.1.0-SNAPSHOT.jar \
  --dubbo.registry.address=N/A --dubbo.application.qos-enable=false
```

检查脚本调用随包的 Java 客户端，使用相同 JDK 17：

```bash
(cd test_scripts && bash rpc_smoke.sh --url tri://127.0.0.1:50071 --name Racing \
  --expected-deployment-id local --expected-scope-id local)
```

Beta 验证需要部署控制器先登记本项目及服务。客户端不指定 `--url` 时，通过 `RACING_REGISTRY_ADDRESS` 或 `DUBBO_REGISTRY_ADDRESS` 发现服务，注册组沿用 `DUBBO_REGISTRY_GROUP`，注册用户名与口令分别通过 `DUBBO_REGISTRY_USERNAME`、`DUBBO_REGISTRY_PASSWORD` 环境变量传入。需要明确 Dubbo 业务分组或版本时，分别配置 `RACING_SERVICE_GROUP`、`RACING_SERVICE_VERSION`。

本机存在代理虚拟网卡或多个网卡时，直接调用使用可达的本机网卡地址；需要固定绑定与注册地址时，分别设置标准 `DUBBO_IP_TO_BIND` 和 `DUBBO_IP_TO_REGISTRY` 环境变量。

泳道验证使用服务发现模式，`--lane` 与 `--url` 互斥。使用 `--lane <泳道名>` 时，客户端携带官方 `dubbo.tag` 附件，并要求指定标签有可用提供者。默认要求响应的流量范围与该标签一致，也可通过 `--expected-scope-id` 明确指定；同时指定预期部署和代际，核对实际响应。主环境请求与指定泳道请求分别执行。直接访问实例地址只证明该实例能够处理 RPC；注册中心发现和泳道路由需要通过服务发现模式及服务端状态确认。
