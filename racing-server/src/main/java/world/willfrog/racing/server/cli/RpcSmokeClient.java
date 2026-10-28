package world.willfrog.racing.server.cli;

import com.google.protobuf.util.JsonFormat;
import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.config.ApplicationConfig;
import org.apache.dubbo.config.ReferenceConfig;
import org.apache.dubbo.config.RegistryConfig;
import org.apache.dubbo.config.bootstrap.DubboBootstrap;
import org.apache.dubbo.rpc.RpcContext;
import world.willfrog.racing.idl.HelloReply;
import world.willfrog.racing.idl.HelloRequest;
import world.willfrog.racing.idl.HelloService;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** 使用生成的 Dubbo 接口发送真实 RPC，核对响应中的部署身份。 */
public final class RpcSmokeClient {

    private static final Set<String> OPTIONS = Set.of("--url", "--name", "--lane", "--timeout-ms",
            "--expected-deployment-id", "--expected-scope-id", "--expected-generation-id");

    private RpcSmokeClient() {
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    private static int run(String[] args) {
        if (args.length == 1 && "--help".equals(args[0])) {
            System.out.println("rpc_smoke.sh [--url tri://host:port] [--name name] [--lane tag] "
                    + "[--expected-deployment-id id] [--expected-scope-id id] "
                    + "[--expected-generation-id id] [--timeout-ms 5000]\n"
                    + "未指定 --url 时，通过 RACING_REGISTRY_ADDRESS 或 DUBBO_REGISTRY_ADDRESS 发现服务；"
                    + "--lane 仅用于服务发现模式。");
            return 0;
        }
        DubboBootstrap bootstrap = null;
        try {
            Map<String, String> options = parseOptions(args);
            String directUrl = options.get("--url");
            String lane = options.get("--lane");
            if (directUrl != null && !directUrl.startsWith("tri://")) {
                throw new IllegalArgumentException("--url requires tri protocol");
            }
            if (directUrl != null && lane != null) {
                throw new IllegalArgumentException("Lane verification requires service discovery");
            }
            String registryAddress = System.getenv("RACING_REGISTRY_ADDRESS");
            if (registryAddress == null || registryAddress.isBlank()) {
                registryAddress = System.getenv("DUBBO_REGISTRY_ADDRESS");
            }
            if (directUrl == null && (registryAddress == null || registryAddress.isBlank())) {
                throw new IllegalArgumentException("Registry address required without --url");
            }

            ApplicationConfig application = new ApplicationConfig("racing-rpc-smoke");
            application.setQosEnable(false);
            application.setRegisterMode("interface");
            RegistryConfig registry = new RegistryConfig(directUrl == null ? registryAddress : "N/A");
            registry.setUseAsConfigCenter(false);
            registry.setUseAsMetadataCenter(false);
            registry.setGroup(System.getenv("DUBBO_REGISTRY_GROUP"));

            ReferenceConfig<HelloService> reference = new ReferenceConfig<>();
            reference.setInterface(HelloService.class);
            reference.setProtocol("tri");
            reference.setInjvm(false);
            reference.setCheck(true);
            reference.setRetries(0);
            int timeout = Integer.parseInt(options.getOrDefault("--timeout-ms", "5000"));
            if (timeout <= 0) {
                throw new IllegalArgumentException("Timeout must be positive");
            }
            reference.setTimeout(timeout);
            reference.setGroup(System.getenv("RACING_SERVICE_GROUP"));
            reference.setVersion(System.getenv("RACING_SERVICE_VERSION"));
            if (directUrl != null) {
                reference.setUrl(directUrl);
            }

            bootstrap = DubboBootstrap.getInstance().application(application).registry(registry).reference(reference);
            bootstrap.start();
            if (lane != null && !lane.isBlank()) {
                RpcContext.getClientAttachment().setAttachment(CommonConstants.TAG_KEY, lane);
                // 选中泳道没有可用提供者时拒绝调用，避免回落到主环境。
                RpcContext.getClientAttachment().setAttachment("dubbo.force.tag", "true");
            }
            String name = options.getOrDefault("--name", "");
            HelloReply reply = reference.get().sayHello(HelloRequest.newBuilder().setName(name).build());
            boolean matches = (name.isBlank() ? "Hello World" : "Hello World, " + name).equals(reply.getMessage())
                    && matches(options.get("--expected-deployment-id"), reply.getDeploymentId())
                    && matches(options.getOrDefault("--expected-scope-id", lane), reply.getTrafficScopeId())
                    && matches(options.get("--expected-generation-id"), reply.getDeploymentGenerationId());
            System.out.println("RACING_RPC_SMOKE_RESULT=" + JsonFormat.printer()
                    .omittingInsignificantWhitespace().print(reply));
            System.out.println("RACING_RPC_SMOKE_MATCH=" + matches);
            return matches ? 0 : 1;
        } catch (Exception error) {
            // 错误类别供自动执行器判定；注册中心地址及凭据不进入摘要。
            System.err.println("RACING_RPC_SMOKE_ERROR=" + error.getClass().getSimpleName());
            return 1;
        } finally {
            RpcContext.getClientAttachment().removeAttachment(CommonConstants.TAG_KEY);
            RpcContext.getClientAttachment().removeAttachment("dubbo.force.tag");
            if (bootstrap != null) {
                bootstrap.destroy();
            }
        }
    }

    private static boolean matches(String expected, String actual) {
        return expected == null || expected.equals(actual);
    }

    private static Map<String, String> parseOptions(String[] args) {
        Map<String, String> options = new HashMap<>();
        for (int index = 0; index < args.length; index += 2) {
            if (!OPTIONS.contains(args[index]) || index + 1 >= args.length) {
                throw new IllegalArgumentException("Unknown or incomplete option");
            }
            options.put(args[index], args[index + 1]);
        }
        return options;
    }
}
