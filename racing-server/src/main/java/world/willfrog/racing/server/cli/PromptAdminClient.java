package world.willfrog.racing.server.cli;

import com.google.protobuf.Message;
import com.google.protobuf.util.JsonFormat;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
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
import world.willfrog.racing.idl.*;

/** 管理命令使用生成的远程接口；凭据只从进程环境读取，不放入请求正文。 */
public final class PromptAdminClient {
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final String KEY_ATTACHMENT = "racing.service-key-id";
    private static final String TOKEN_ATTACHMENT = "racing.service-token";
    private static final String FORCE_TAG = "dubbo.force.tag";
    private static final Set<String> OPTIONS = Set.of("--operation", "--request", "--url", "--timeout-ms");
    private static final Map<String, Class<? extends Message>> REQUESTS = Map.ofEntries(
            Map.entry("CreatePrompt", CreatePromptRequest.class),
            Map.entry("UpdatePromptMetadata", UpdatePromptMetadataRequest.class),
            Map.entry("CreatePromptVersion", CreatePromptVersionRequest.class),
            Map.entry("UpdatePromptVersionAuthorization", UpdatePromptVersionAuthorizationRequest.class),
            Map.entry("GetPrompt", GetPromptRequest.class),
            Map.entry("ListPrompts", ListPromptsRequest.class),
            Map.entry("ListPromptVersions", ListPromptVersionsRequest.class),
            Map.entry("SetPromptDefault", SetPromptDefaultRequest.class),
            Map.entry("ReadPromptVersions", ReadPromptVersionsRequest.class),
            Map.entry("CreatePromptSet", CreatePromptSetRequest.class),
            Map.entry("UpdatePromptSetMetadata", UpdatePromptSetMetadataRequest.class),
            Map.entry("CreatePromptSetVersion", CreatePromptSetVersionRequest.class),
            Map.entry("GetPromptSet", GetPromptSetRequest.class),
            Map.entry("ListPromptSets", ListPromptSetsRequest.class),
            Map.entry("ListPromptSetVersions", ListPromptSetVersionsRequest.class),
            Map.entry("SetPromptSetDefault", SetPromptSetDefaultRequest.class),
            Map.entry("GetPromptSetDefault", GetPromptSetDefaultRequest.class),
            Map.entry("ReadPromptSetVersion", ReadPromptSetVersionRequest.class),
            Map.entry("ResolvePromptSelection", ResolvePromptSelectionRequest.class),
            Map.entry("GetPromptSelection", GetPromptSelectionRequest.class),
            Map.entry("ListPromptReferences", ListPromptReferencesRequest.class),
            Map.entry("DeletePromptVersion", DeletePromptVersionRequest.class),
            Map.entry("DeletePromptSetVersion", DeletePromptSetVersionRequest.class),
            Map.entry("StoreModelInput", StoreModelInputRequest.class),
            Map.entry("GetModelInput", GetModelInputRequest.class));

    private PromptAdminClient() { }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] args) {
        if (args.length == 1 && args[0].equals("--help")) {
            System.out.println("prompt_admin.sh --operation <方法名> --request <JSON文件或-> "
                    + "[--url tri://host:port] [--timeout-ms 10000]\n"
                    + "请求使用Protobuf JSON格式，context明确目标范围、部署及代际。"
                    + "RACING_SERVICE_KEY_ID与RACING_SERVICE_TOKEN只从环境读取。\n"
                    + "未指定直连地址时使用RACING_REGISTRY_ADDRESS或DUBBO_REGISTRY_ADDRESS。\n"
                    + "可用方法：" + String.join(", ", REQUESTS.keySet().stream().sorted().toList()));
            return 0;
        }
        DubboBootstrap bootstrap = null;
        Map<String, String> previous = new HashMap<>();
        boolean attachmentsInstalled = false;
        try {
            Map<String, String> options = options(args);
            String operation = required(options.get("--operation"));
            Class<? extends Message> type = REQUESTS.get(operation);
            if (type == null) throw new IllegalArgumentException("不支持的提示词管理方法");
            String requestPath = required(options.get("--request"));
            byte[] bytes = requestPath.equals("-") ? System.in.readAllBytes() : Files.readAllBytes(Path.of(requestPath));
            String json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            var document = JSON.readTree(json);
            if (document == null || !document.isObject()) {
                throw new IllegalArgumentException("管理请求必须为完整JSON对象");
            }
            requireLosslessUtf8(document);
            Message.Builder builder = (Message.Builder) type.getMethod("newBuilder").invoke(null);
            JsonFormat.parser().merge(json, builder);
            Message request = builder.build();
            RuntimeCallContext context = (RuntimeCallContext) request.getField(
                    request.getDescriptorForType().findFieldByName("context"));
            TargetIdentity target = context.getTarget();
            required(target.getEnvironment());
            required(target.getTargetScopeInstanceId());
            required(target.getTargetDeploymentId());
            required(target.getExpectedTargetGenerationId());
            String key = required(System.getenv("RACING_SERVICE_KEY_ID"));
            String token = required(System.getenv("RACING_SERVICE_TOKEN"));
            String url = options.get("--url");
            if (url != null && !url.startsWith("tri://")) throw new IllegalArgumentException("仅支持tri地址");
            String registryAddress = System.getenv("RACING_REGISTRY_ADDRESS");
            if (registryAddress == null || registryAddress.isBlank()) registryAddress = System.getenv("DUBBO_REGISTRY_ADDRESS");
            if (url == null) required(registryAddress);
            int timeout = Integer.parseInt(options.getOrDefault("--timeout-ms", "10000"));
            if (timeout <= 0) throw new IllegalArgumentException("调用超时必须为正数");

            ApplicationConfig application = new ApplicationConfig("racing-prompt-admin");
            application.setQosEnable(false);
            application.setRegisterMode("interface");
            RegistryConfig registry = new RegistryConfig(url == null ? registryAddress : "N/A");
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

            for (String attachment : Set.of(KEY_ATTACHMENT, TOKEN_ATTACHMENT, FORCE_TAG, CommonConstants.TAG_KEY)) {
                previous.put(attachment, RpcContext.getClientAttachment().getAttachment(attachment));
            }
            attachmentsInstalled = true;
            attachment(KEY_ATTACHMENT, key);
            attachment(TOKEN_ATTACHMENT, token);
            String tag = target.getTargetRouteTag();
            attachment(CommonConstants.TAG_KEY, tag.isBlank() ? null : tag);
            attachment(FORCE_TAG, tag.isBlank() ? null : "true");
            String methodName = Character.toLowerCase(operation.charAt(0)) + operation.substring(1);
            // 只反射上面固定清单中的生成方法，操作名不能指定任意类或Java方法。
            Method method = RacingService.class.getMethod(methodName, type);
            Message reply = (Message) method.invoke(reference.get(), request);
            var result = reply.getDescriptorForType().getOneofs().stream()
                    .filter(oneof -> oneof.getName().equals("result")).findFirst().orElseThrow();
            var selected = reply.getOneofFieldDescriptor(result);
            if (selected == null) throw new IllegalStateException("平台回复未确认结果");
            if (!selected.getName().equals("error")) {
                RuntimeIdentity actual = (RuntimeIdentity) reply.getField(
                        reply.getDescriptorForType().findFieldByName("actual_target"));
                if (!matches(target, actual)) throw new IllegalStateException("平台实际目标与请求目标不一致");
            }
            System.out.println(JsonFormat.printer().omittingInsignificantWhitespace().print(reply));
            return selected.getName().equals("error") ? 1 : 0;
        } catch (Exception error) {
            // 不回显请求、远程异常正文、注册中心地址或凭据。
            System.err.println("RACING_PROMPT_ADMIN_ERROR=" + error.getClass().getSimpleName());
            return 1;
        } finally {
            if (attachmentsInstalled) previous.forEach(PromptAdminClient::attachment);
            if (bootstrap != null) bootstrap.destroy();
        }
    }

    private static boolean matches(TargetIdentity expected, RuntimeIdentity actual) {
        return expected.getEnvironment().equals(actual.getEnvironment())
                && expected.getTargetScopeInstanceId().equals(actual.getScopeInstanceId())
                && expected.getTargetDeploymentId().equals(actual.getDeploymentId())
                && expected.getTargetRouteTag().equals(actual.getRouteTag())
                && expected.getExpectedTargetGenerationId().equals(actual.getActualTargetGenerationId());
    }

    private static void attachment(String key, String value) {
        if (value == null) RpcContext.getClientAttachment().removeAttachment(key);
        else RpcContext.getClientAttachment().setAttachment(key, value);
    }

    private static void requireLosslessUtf8(JsonNode value) throws CharacterCodingException {
        if (value.isTextual()) {
            requireLosslessUtf8(value.textValue());
        } else if (value.isObject()) {
            var fields = value.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                requireLosslessUtf8(field.getKey());
                requireLosslessUtf8(field.getValue());
            }
        } else if (value.isArray()) {
            for (JsonNode element : value) requireLosslessUtf8(element);
        }
    }

    private static void requireLosslessUtf8(String value) throws CharacterCodingException {
        StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value));
    }

    private static String required(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("缺少必需的调用参数");
        return value;
    }

    private static Map<String, String> options(String[] args) {
        Map<String, String> options = new HashMap<>();
        for (int index = 0; index < args.length; index += 2) {
            if (!OPTIONS.contains(args[index]) || index + 1 >= args.length
                    || options.putIfAbsent(args[index], args[index + 1]) != null) {
                throw new IllegalArgumentException("未知、重复或不完整的参数");
            }
        }
        return options;
    }
}
