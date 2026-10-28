package world.willfrog.racing.server.cli;

import com.google.protobuf.Message;
import com.google.protobuf.util.JsonFormat;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.config.ApplicationConfig;
import org.apache.dubbo.config.ReferenceConfig;
import org.apache.dubbo.config.RegistryConfig;
import org.apache.dubbo.config.bootstrap.DubboBootstrap;
import org.apache.dubbo.rpc.RpcContext;
import world.willfrog.racing.idl.CreateExperimentRequest;
import world.willfrog.racing.idl.EndExperimentRequest;
import world.willfrog.racing.idl.ExperimentMutationRequest;
import world.willfrog.racing.idl.GetExperimentRequest;
import world.willfrog.racing.idl.ListExperimentsRequest;
import world.willfrog.racing.idl.RacingService;
import world.willfrog.racing.idl.RuntimeCallContext;
import world.willfrog.racing.idl.RuntimeIdentity;
import world.willfrog.racing.idl.SaveExperimentDraftRequest;
import world.willfrog.racing.idl.TargetIdentity;

/** 读取管理请求文件，通过生成的接口调用；凭据只放在本次RPC附件中。 */
public final class ExperimentConfigurationClient {
    private static final String KEY_ID = "racing.service-key-id";
    private static final String TOKEN = "racing.service-token";
    private static final String FORCE_TAG = "dubbo.force.tag";
    private static final Set<String> OPTIONS = Set.of("--action", "--request", "--url", "--timeout-ms");

    private ExperimentConfigurationClient() {
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    private static int run(String[] args) {
        if (args.length == 1 && "--help".equals(args[0])) {
            System.out.println("experiment_config.sh --action create|save|publish|start|pause|resume|end|"
                    + "preview-delete|delete|get|list|versions|operations --request request.json "
                    + "[--url tri://host:port] [--timeout-ms 10000]\n"
                    + "请求文件使用对应Protobuf消息的JSON格式，context中明确目标身份。"
                    + "服务凭据来自RACING_SERVICE_KEY_ID及RACING_SERVICE_TOKEN。"
                    + "未提供--url时使用RACING_REGISTRY_ADDRESS或DUBBO_REGISTRY_ADDRESS；"
                    + "直连成功不能代替服务发现的泳道路由验收。");
            return 0;
        }
        DubboBootstrap bootstrap = null;
        Map<String, String> prior = new HashMap<>();
        boolean attachmentsChanged = false;
        try {
            Map<String, String> options = parseOptions(args);
            String action = required(options.get("--action"));
            Message.Builder builder = requestBuilder(action);
            JsonFormat.parser().merge(Files.readString(Path.of(required(options.get("--request")))), builder);
            Message request = builder.build();
            RuntimeCallContext context = (RuntimeCallContext) request.getField(
                    request.getDescriptorForType().findFieldByName("context"));
            TargetIdentity target = context.getTarget();
            required(target.getEnvironment());
            required(target.getTargetScopeInstanceId());
            required(target.getTargetDeploymentId());
            required(target.getExpectedTargetGenerationId());
            required(context.getCallerGenerationId());
            String keyId = required(System.getenv("RACING_SERVICE_KEY_ID"));
            String token = required(System.getenv("RACING_SERVICE_TOKEN"));
            String url = options.get("--url");
            if (url != null && !url.startsWith("tri://")) throw new IllegalArgumentException("需要tri直连地址");
            String address = System.getenv("RACING_REGISTRY_ADDRESS");
            if (address == null || address.isBlank()) address = System.getenv("DUBBO_REGISTRY_ADDRESS");
            if (url == null) required(address);
            int timeout = Integer.parseInt(options.getOrDefault("--timeout-ms", "10000"));
            if (timeout <= 0) throw new IllegalArgumentException("超时需要正整数");

            ApplicationConfig application = new ApplicationConfig("racing-experiment-management");
            application.setQosEnable(false);
            application.setRegisterMode("interface");
            RegistryConfig registry = new RegistryConfig(url == null ? address : "N/A");
            registry.setUseAsConfigCenter(false);
            registry.setUseAsMetadataCenter(false);
            registry.setGroup(System.getenv("DUBBO_REGISTRY_GROUP"));
            if (url == null) {
                registry.setUsername(System.getenv("DUBBO_REGISTRY_USERNAME"));
                registry.setPassword(System.getenv("DUBBO_REGISTRY_PASSWORD"));
            }
            ReferenceConfig<RacingService> reference = new ReferenceConfig<>();
            reference.setInterface(RacingService.class);
            reference.setProtocol("tri");
            reference.setInjvm(false);
            reference.setCheck(true);
            reference.setRetries(0);
            reference.setTimeout(timeout);
            reference.setGroup(System.getenv("RACING_SERVICE_GROUP"));
            reference.setVersion(System.getenv("RACING_SERVICE_VERSION"));
            if (url != null) reference.setUrl(url);
            bootstrap = DubboBootstrap.getInstance().application(application).registry(registry).reference(reference);
            bootstrap.start();
            for (String key : Set.of(KEY_ID, TOKEN, CommonConstants.TAG_KEY, FORCE_TAG)) {
                prior.put(key, RpcContext.getClientAttachment().getAttachment(key));
            }
            attachmentsChanged = true;
            setAttachment(KEY_ID, keyId);
            setAttachment(TOKEN, token);
            String route = target.getTargetRouteTag();
            boolean tagged = !route.isBlank() && !"main-beta".equals(route);
            setAttachment(CommonConstants.TAG_KEY, tagged ? route : null);
            setAttachment(FORCE_TAG, tagged ? "true" : null);
            Message reply = invoke(action, request, reference.get());
            var errorField = reply.getDescriptorForType().findFieldByName("error");
            if (reply.hasField(errorField)) {
                System.out.println("RACING_EXPERIMENT_ERROR=" + JsonFormat.printer()
                        .omittingInsignificantWhitespace().print((Message) reply.getField(errorField)));
                return 2;
            }
            RuntimeIdentity actual = (RuntimeIdentity) reply.getField(
                    reply.getDescriptorForType().findFieldByName("actual_target"));
            if (!sameTarget(target, actual)) throw new IllegalStateException("实际处理服务与预期目标不一致");
            System.out.println("RACING_EXPERIMENT_RESULT=" + JsonFormat.printer()
                    .omittingInsignificantWhitespace().print(reply));
            return 0;
        } catch (Exception error) {
            // 请求可能包含正文，连接异常可能含凭据地址；这里只输出错误类别。
            System.err.println("RACING_EXPERIMENT_CLIENT_ERROR=" + error.getClass().getSimpleName());
            return 1;
        } finally {
            if (attachmentsChanged) prior.forEach(ExperimentConfigurationClient::setAttachment);
            if (bootstrap != null) bootstrap.destroy();
        }
    }

    private static Message.Builder requestBuilder(String action) {
        return switch (action) {
            case "create" -> CreateExperimentRequest.newBuilder();
            case "save" -> SaveExperimentDraftRequest.newBuilder();
            case "publish", "start", "pause", "resume", "delete" -> ExperimentMutationRequest.newBuilder();
            case "end" -> EndExperimentRequest.newBuilder();
            case "get", "preview-delete", "versions", "operations" -> GetExperimentRequest.newBuilder();
            case "list" -> ListExperimentsRequest.newBuilder();
            default -> throw new IllegalArgumentException("未知管理动作");
        };
    }

    private static Message invoke(String action, Message request, RacingService service) {
        return switch (action) {
            case "create" -> service.createExperiment((CreateExperimentRequest) request);
            case "save" -> service.saveExperimentDraft((SaveExperimentDraftRequest) request);
            case "publish" -> service.publishExperiment((ExperimentMutationRequest) request);
            case "start" -> service.startExperiment((ExperimentMutationRequest) request);
            case "pause" -> service.pauseExperiment((ExperimentMutationRequest) request);
            case "resume" -> service.resumeExperiment((ExperimentMutationRequest) request);
            case "end" -> service.endExperiment((EndExperimentRequest) request);
            case "preview-delete" -> service.previewExperimentDelete((GetExperimentRequest) request);
            case "delete" -> service.deleteExperiment((ExperimentMutationRequest) request);
            case "get" -> service.getExperiment((GetExperimentRequest) request);
            case "list" -> service.listExperiments((ListExperimentsRequest) request);
            case "versions" -> service.listExperimentVersions((GetExperimentRequest) request);
            case "operations" -> service.listExperimentOperations((GetExperimentRequest) request);
            default -> throw new IllegalArgumentException("未知管理动作");
        };
    }

    private static boolean sameTarget(TargetIdentity expected, RuntimeIdentity actual) {
        return expected.getEnvironment().equals(actual.getEnvironment())
                && expected.getTargetScopeInstanceId().equals(actual.getScopeInstanceId())
                && expected.getTargetRouteTag().equals(actual.getRouteTag())
                && expected.getTargetDeploymentId().equals(actual.getDeploymentId())
                && expected.getExpectedTargetGenerationId().equals(actual.getActualTargetGenerationId());
    }

    private static String required(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("缺少必需的请求或调用配置");
        return value;
    }

    private static Map<String, String> parseOptions(String[] args) {
        Map<String, String> options = new HashMap<>();
        for (int i = 0; i < args.length; i += 2) {
            if (i + 1 >= args.length || !OPTIONS.contains(args[i]) || options.putIfAbsent(args[i], args[i + 1]) != null)
                throw new IllegalArgumentException("参数未知、重复或缺少值");
        }
        return options;
    }

    private static void setAttachment(String key, String value) {
        if (value == null) RpcContext.getClientAttachment().removeAttachment(key);
        else RpcContext.getClientAttachment().setAttachment(key, value);
    }
}
