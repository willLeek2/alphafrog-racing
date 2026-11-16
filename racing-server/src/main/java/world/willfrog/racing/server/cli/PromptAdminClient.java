package world.willfrog.racing.server.cli;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.Message;
import com.google.protobuf.util.JsonFormat;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.config.ApplicationConfig;
import org.apache.dubbo.config.ReferenceConfig;
import org.apache.dubbo.config.RegistryConfig;
import org.apache.dubbo.config.bootstrap.DubboBootstrap;
import org.apache.dubbo.rpc.RpcContext;
import world.willfrog.racing.idl.*;

/** 管理命令使用生成的远程接口。单条模式每次启动执行一条；批次模式同一次启动按顺序执行多条。凭据只从进程环境放入调用附件。 */
public final class PromptAdminClient {
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final String KEY_ATTACHMENT = "racing.service-key-id";
    private static final String TOKEN_ATTACHMENT = "racing.service-token";
    private static final String FORCE_TAG = "dubbo.force.tag";
    private static final Set<String> OPTIONS = Set.of(
            "--operation", "--request", "--url", "--timeout-ms", "--batch", "--out-dir");
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
        return run(args, PromptAdminClient::openDubbo);
    }

    static int run(String[] args, SessionFactory factory) {
        if (args.length == 1 && args[0].equals("--help")) {
            System.out.println("prompt_admin.sh --operation <方法名> --request <JSON文件或-> "
                    + "[--url tri://host:port] [--timeout-ms 10000]\n"
                    + "prompt_admin.sh --batch <批次文件> --out-dir <回执目录> "
                    + "[--url tri://host:port] [--timeout-ms 10000]\n"
                    + "请求使用Protobuf JSON格式，context明确目标范围、部署及代际。"
                    + "RACING_SERVICE_KEY_ID与RACING_SERVICE_TOKEN只从环境读取，不从参数或批次文件读取。\n"
                    + "未指定直连地址时使用RACING_REGISTRY_ADDRESS或DUBBO_REGISTRY_ADDRESS。\n"
                    + "批次文件可以是JSON数组，或含items、pendingActions数组的对象。"
                    + "元素必须含operation与request，可以带key。request为请求文件路径或内嵌JSON对象。"
                    + "路径相对批次文件所在目录。\n"
                    + "回执写入<out-dir>/<key>.json；未给key时使用请求文件名。"
                    + "文件内容与单条标准输出的JSON对象一致。\n"
                    + "标准输出每条一行：OK <key> 或 FAILED <key> <错误码>。"
                    + "任一条目失败时继续后续条目。全部成功时进程返回0，否则返回1。\n"
                    + "可用方法：" + String.join(", ", REQUESTS.keySet().stream().sorted().toList()));
            return 0;
        }
        try {
            Map<String, String> options = options(args);
            if (options.containsKey("--batch") || options.containsKey("--out-dir")) {
                if (!options.containsKey("--batch") || !options.containsKey("--out-dir")
                        || options.containsKey("--operation") || options.containsKey("--request")) {
                    throw new IllegalArgumentException("未知、重复或不完整的参数");
                }
                return runBatch(options, factory);
            }
            return runSingle(options, factory);
        } catch (Exception error) {
            System.err.println("RACING_PROMPT_ADMIN_ERROR=" + error.getClass().getSimpleName());
            return 1;
        }
    }

    private static int runSingle(Map<String, String> options, SessionFactory factory) throws Exception {
        String operation = required(options.get("--operation"));
        String requestPath = required(options.get("--request"));
        String json = requestPath.equals("-")
                ? readUtf8(System.in.readAllBytes())
                : readFile(Path.of(requestPath));
        Prepared prepared = prepare(operation, json);
        RemoteSession session = null;
        Outcome outcome;
        try {
            session = factory.open(connectionSettings(options));
            outcome = invokePrepared(prepared, session);
        } catch (Exception error) {
            outcome = Outcome.failed(error);
        } finally {
            if (session != null) session.close();
        }
        System.out.print(outcome.stdout());
        System.err.print(outcome.stderr());
        return outcome.code();
    }

    private static int runBatch(Map<String, String> options, SessionFactory factory) throws Exception {
        Path batchFile = Path.of(required(options.get("--batch"))).toAbsolutePath().normalize();
        Path receiptDir = Path.of(required(options.get("--out-dir"))).toAbsolutePath().normalize();
        List<BatchEntry> entries = loadBatch(batchFile);
        ensureDistinctKeys(entries);
        Files.createDirectories(receiptDir);
        assertReceiptsAbsent(receiptDir, entries);
        SessionHolder holder = new SessionHolder(factory, connectionSettings(options));
        boolean failed = false;
        try {
            for (BatchEntry entry : entries) {
                Outcome outcome = execute(entry, holder);
                if (!outcome.stdout().isEmpty()) {
                    writeNew(receiptDir.resolve(entry.key() + ".json"), outcome.stdout());
                }
                System.out.print(statusLine(entry.key(), outcome));
                if (outcome.code() != 0) failed = true;
            }
        } finally {
            holder.close();
        }
        return failed ? 1 : 0;
    }

    private static Outcome execute(BatchEntry entry, SessionHolder holder) {
        if (entry.failure() != null) return Outcome.failed(entry.failure());
        try {
            Prepared prepared = prepare(entry.operation(), entry.json());
            return invokePrepared(prepared, holder.get());
        } catch (Exception error) {
            return Outcome.failed(error);
        }
    }

    private static List<BatchEntry> loadBatch(Path batchFile) throws Exception {
        JsonNode document = JSON.readTree(readFile(batchFile));
        JsonNode items = batchItems(document);
        List<BatchEntry> entries = new ArrayList<>();
        for (int index = 0; index < items.size(); index++) {
            JsonNode element = items.get(index);
            try {
                entries.add(parseElement(batchFile, element, index));
            } catch (Exception error) {
                entries.add(BatchEntry.failed(fallbackKey(element, index), error));
            }
        }
        return entries;
    }

    private static JsonNode batchItems(JsonNode document) {
        JsonNode items = document;
        if (document != null && document.isObject()) {
            if (document.has("items")) items = document.get("items");
            else if (document.has("pendingActions")) items = document.get("pendingActions");
            else throw new IllegalArgumentException("批次文件必须为非空JSON数组");
        }
        if (items == null || !items.isArray() || items.isEmpty()) {
            throw new IllegalArgumentException("批次文件必须为非空JSON数组");
        }
        return items;
    }

    private static BatchEntry parseElement(Path batchFile, JsonNode element, int index) throws Exception {
        if (!element.isObject()) throw new IllegalArgumentException("批次元素必须为JSON对象");
        String explicitKey = null;
        JsonNode operationNode = null;
        JsonNode requestNode = null;
        var fields = element.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            String name = field.getKey();
            if (credentialField(name)) throw new IllegalArgumentException("批次元素不能携带凭据");
            if (name.equals("operation")) operationNode = field.getValue();
            else if (name.equals("request")) requestNode = field.getValue();
            else if (name.equals("key")) {
                if (!field.getValue().isTextual()) throw new IllegalArgumentException("回执名称无效");
                explicitKey = field.getValue().textValue();
            }
        }
        if (operationNode == null || !operationNode.isTextual()) {
            throw new IllegalArgumentException("缺少必需的调用参数");
        }
        String operation = required(operationNode.textValue());
        requireLosslessUtf8(operation);
        if (requestNode == null || requestNode.isNull()) throw new IllegalArgumentException("缺少必需的调用参数");
        if (requestNode.isTextual()) {
            String requestPath = required(requestNode.textValue());
            if (requestPath.equals("-")) throw new IllegalArgumentException("批次中的请求不能从标准输入读取");
            requireLosslessUtf8(requestPath);
            String key = receiptKey(explicitKey, requestPath, index);
            return BatchEntry.ready(operation, readFile(resolveRequest(batchFile, requestPath)), key);
        }
        if (!requestNode.isObject()) throw new IllegalArgumentException("管理请求必须为完整JSON对象");
        requireLosslessUtf8(requestNode);
        return BatchEntry.ready(operation, JSON.writeValueAsString(requestNode), receiptKey(explicitKey, null, index));
    }

    private static String fallbackKey(JsonNode element, int index) {
        try {
            if (element != null && element.isObject()) {
                String explicit = element.has("key") && element.get("key").isTextual() ? element.get("key").textValue() : null;
                String path = element.has("request") && element.get("request").isTextual()
                        ? element.get("request").textValue() : null;
                return receiptKey(explicit, path, index);
            }
        } catch (RuntimeException ignored) {
            // 名称无效时退回序号，这一条仍然有状态行。
        }
        return "%04d".formatted(index + 1);
    }

    private static String receiptKey(String explicit, String requestPath, int index) {
        String raw = explicit;
        if (raw == null || raw.isBlank()) {
            if (requestPath != null) {
                String name = Path.of(requestPath).getFileName().toString();
                if (name.endsWith(".json")) name = name.substring(0, name.length() - ".json".length());
                raw = name;
            } else {
                raw = "%04d".formatted(index + 1);
            }
        }
        if (raw == null || raw.isBlank() || raw.contains("/") || raw.contains("\\") || raw.contains("..")
                || raw.indexOf('\0') >= 0 || raw.indexOf(' ') >= 0 || raw.indexOf('\n') >= 0 || raw.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("回执名称无效");
        }
        return raw;
    }

    private static boolean credentialField(String name) {
        String compact = name.replace("-", "").replace("_", "").replace(".", "").toLowerCase(Locale.ROOT);
        return compact.contains("token") || compact.contains("password") || compact.contains("secret")
                || compact.equals("authorization") || compact.equals("servicekey");
    }

    private static void ensureDistinctKeys(List<BatchEntry> entries) {
        Set<String> seen = new HashSet<>();
        for (BatchEntry entry : entries) {
            if (!seen.add(entry.key())) throw new IllegalArgumentException("回执名称重复");
        }
    }

    private static String statusLine(String key, Outcome outcome) {
        if (outcome.code() == 0) return "OK " + key + System.lineSeparator();
        return "FAILED " + key + " " + outcome.statusCode() + System.lineSeparator();
    }

    private static Path resolveRequest(Path batchFile, String requestPath) {
        Path path = Path.of(requestPath);
        if (path.isAbsolute()) return path.normalize();
        Path parent = batchFile.getParent();
        if (parent == null) return path.normalize();
        return parent.resolve(path).normalize();
    }

    private static Prepared prepare(String operation, String json) throws Exception {
        Class<? extends Message> type = REQUESTS.get(operation);
        if (type == null) throw new IllegalArgumentException("不支持的提示词管理方法");
        JsonNode document = JSON.readTree(json);
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
        return new Prepared(operation, type, request, target);
    }

    private static Outcome invokePrepared(Prepared prepared, RemoteSession session) throws Exception {
        Message reply = session.invoke(prepared.operation(), prepared.type(), prepared.request(), prepared.target());
        var result = reply.getDescriptorForType().getOneofs().stream()
                .filter(oneof -> oneof.getName().equals("result")).findFirst().orElseThrow();
        var selected = reply.getOneofFieldDescriptor(result);
        if (selected == null) throw new IllegalStateException("平台回复未确认结果");
        if (!selected.getName().equals("error")) {
            RuntimeIdentity actual = (RuntimeIdentity) reply.getField(
                    reply.getDescriptorForType().findFieldByName("actual_target"));
            if (!matches(prepared.target(), actual)) throw new IllegalStateException("平台实际目标与请求目标不一致");
        }
        String json = JsonFormat.printer().omittingInsignificantWhitespace().print(reply);
        if (selected.getName().equals("error")) {
            Object value = reply.getField(selected);
            String code = value instanceof Message error ? errorCode(error) : "error";
            return Outcome.reply(1, json, code);
        }
        return Outcome.reply(0, json, "ok");
    }

    private static String errorCode(Message error) {
        var field = error.getDescriptorForType().findFieldByName("code");
        if (field == null || field.isRepeated() || !error.hasField(field)) return "error";
        String code = String.valueOf(error.getField(field)).trim().replaceAll("\\s+", "_");
        return code.isEmpty() ? "error" : code;
    }

    private static ConnectionSettings connectionSettings(Map<String, String> options) {
        return new ConnectionSettings(options.get("--url"), options.getOrDefault("--timeout-ms", "10000"));
    }

    private static RemoteSession openDubbo(ConnectionSettings settings) throws Exception {
        String key = required(System.getenv("RACING_SERVICE_KEY_ID"));
        String token = required(System.getenv("RACING_SERVICE_TOKEN"));
        String url = settings.url();
        if (url != null && !url.startsWith("tri://")) throw new IllegalArgumentException("仅支持tri地址");
        String registryAddress = System.getenv("RACING_REGISTRY_ADDRESS");
        if (registryAddress == null || registryAddress.isBlank()) registryAddress = System.getenv("DUBBO_REGISTRY_ADDRESS");
        if (url == null) required(registryAddress);
        int timeout = Integer.parseInt(settings.timeoutText());
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
        DubboBootstrap bootstrap = null;
        try {
            bootstrap = DubboBootstrap.getInstance().application(application).registry(registry).reference(reference);
            bootstrap.start();
            return new DubboSession(bootstrap, reference, key, token);
        } catch (Exception error) {
            if (bootstrap != null) bootstrap.destroy();
            throw error;
        }
    }

    private static void assertReceiptsAbsent(Path receiptDir, List<BatchEntry> entries) {
        for (BatchEntry entry : entries) {
            if (Files.exists(receiptDir.resolve(entry.key() + ".json"))) {
                throw new IllegalStateException("回执文件已存在");
            }
        }
    }

    private static void writeNew(Path path, String text) throws java.io.IOException {
        Files.writeString(path, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
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

    private static String readFile(Path path) throws java.io.IOException {
        return readUtf8(Files.readAllBytes(path));
    }

    private static String readUtf8(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
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

    @FunctionalInterface
    interface SessionFactory {
        RemoteSession open(ConnectionSettings settings) throws Exception;
    }

    interface RemoteSession extends AutoCloseable {
        Message invoke(String operation, Class<? extends Message> type, Message request, TargetIdentity target)
                throws Exception;

        @Override
        void close();
    }

    record ConnectionSettings(String url, String timeoutText) { }

    private record Prepared(String operation, Class<? extends Message> type, Message request, TargetIdentity target) { }

    private record BatchEntry(String operation, String json, String key, Exception failure) {
        private static BatchEntry ready(String operation, String json, String key) {
            return new BatchEntry(operation, json, key, null);
        }

        private static BatchEntry failed(String key, Exception failure) {
            return new BatchEntry(null, null, key, failure);
        }
    }

    private record Outcome(int code, String stdout, String stderr, String statusCode) {
        private static Outcome failed(Exception error) {
            String name = error.getClass().getSimpleName();
            return new Outcome(1, "", "RACING_PROMPT_ADMIN_ERROR=" + name + System.lineSeparator(), name);
        }

        private static Outcome reply(int code, String json, String statusCode) {
            return new Outcome(code, json + System.lineSeparator(), "", statusCode);
        }
    }

    private static final class SessionHolder {
        private final SessionFactory factory;
        private final ConnectionSettings settings;
        private RemoteSession session;
        private Exception failure;

        private SessionHolder(SessionFactory factory, ConnectionSettings settings) {
            this.factory = factory;
            this.settings = settings;
        }

        private RemoteSession get() throws Exception {
            if (failure != null) throw failure;
            if (session == null) {
                try {
                    session = factory.open(settings);
                } catch (Exception error) {
                    failure = error;
                    throw error;
                }
            }
            return session;
        }

        private void close() throws Exception {
            if (session != null) session.close();
        }
    }

    private static final class DubboSession implements RemoteSession {
        private final DubboBootstrap bootstrap;
        private final ReferenceConfig<RacingService> reference;
        private final String key;
        private final String token;
        private final Map<String, String> previous = new HashMap<>();
        private RacingService service;
        private Exception lookupFailure;
        private boolean attachmentsInstalled;

        private DubboSession(DubboBootstrap bootstrap, ReferenceConfig<RacingService> reference, String key, String token) {
            this.bootstrap = bootstrap;
            this.reference = reference;
            this.key = key;
            this.token = token;
            for (String attachment : Set.of(KEY_ATTACHMENT, TOKEN_ATTACHMENT, FORCE_TAG, CommonConstants.TAG_KEY)) {
                previous.put(attachment, RpcContext.getClientAttachment().getAttachment(attachment));
            }
            attachmentsInstalled = true;
        }

        @Override
        public Message invoke(String operation, Class<? extends Message> type, Message request, TargetIdentity target)
                throws Exception {
            if (lookupFailure != null) throw lookupFailure;
            try {
                if (service == null) service = reference.get();
            } catch (Exception error) {
                lookupFailure = error;
                throw error;
            }
            attachment(KEY_ATTACHMENT, key);
            attachment(TOKEN_ATTACHMENT, token);
            String tag = target.getTargetRouteTag();
            attachment(CommonConstants.TAG_KEY, tag.isBlank() ? null : tag);
            attachment(FORCE_TAG, tag.isBlank() ? null : "true");
            String methodName = Character.toLowerCase(operation.charAt(0)) + operation.substring(1);
            Method method = RacingService.class.getMethod(methodName, type);
            return (Message) method.invoke(service, request);
        }

        @Override
        public void close() {
            if (attachmentsInstalled) previous.forEach(PromptAdminClient::attachment);
            bootstrap.destroy();
        }
    }
}
