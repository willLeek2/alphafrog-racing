package world.willfrog.racing.server.security;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import org.apache.dubbo.rpc.RpcContext;
import org.springframework.stereotype.Component;
import world.willfrog.racing.idl.ConfirmedRuntimeContext;
import world.willfrog.racing.idl.RootIdentity;
import world.willfrog.racing.idl.RuntimeCallContext;
import world.willfrog.racing.idl.RuntimeIdentity;
import world.willfrog.racing.idl.TargetIdentity;
import world.willfrog.racing.server.config.RacingRuntimeProperties;

/** 每次调用先核对服务凭据、动作和范围，再允许业务访问数据。 */
@Component
public final class ServiceAuthorization {
    public static final String KEY_ID_ATTACHMENT = "racing.service-key-id";
    public static final String TOKEN_ATTACHMENT = "racing.service-token";
    private static final Set<String> ROOT_REQUIRED = Set.of(
            "Evaluate", "RecordExperimentUsage", "ResolvePromptSelection", "GetPromptSelection", "StoreModelInput");
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final RacingRuntimeProperties properties;

    public ServiceAuthorization(RacingRuntimeProperties properties) {
        this.properties = properties;
    }

    public AuthorizedCall authorize(String action, RuntimeCallContext context) {
        requireText(action, "action");
        if (context == null || !context.hasTarget()) throw invalid("缺少实验平台目标范围");
        Grant grant = authenticate();
        if ("GetModelInput".equals(action) || !grant.actions().contains(action)) {
            throw RacingServiceException.failure("FORBIDDEN", "执行服务没有该动作的权限");
        }
        RuntimeIdentity actual = properties.currentIdentity();
        TargetIdentity target = context.getTarget();
        if (!grant.environment().equals(target.getEnvironment())
                || !grant.targetScopeInstanceId().equals(target.getTargetScopeInstanceId())) {
            throw RacingServiceException.failure("FORBIDDEN", "执行服务没有该目标范围的权限");
        }
        if (!actual.getEnvironment().equals(target.getEnvironment())
                || !actual.getScopeInstanceId().equals(target.getTargetScopeInstanceId())
                || !actual.getRouteTag().equals(target.getTargetRouteTag())
                || !actual.getDeploymentId().equals(target.getTargetDeploymentId())
                || !actual.getActualTargetGenerationId().equals(target.getExpectedTargetGenerationId())) {
            throw RacingServiceException.failure("SCOPE_MISMATCH", "请求目标与实际实验平台实例不一致");
        }
        if (ROOT_REQUIRED.contains(action) && !context.hasRoot()) throw invalid("业务动作缺少根归属");
        if (context.hasRoot()) {
            RootIdentity root = context.getRoot();
            requireText(root.getOwnerNamespace(), "root.owner_namespace");
            requireText(root.getOwnerKind(), "root.owner_kind");
            requireText(root.getRootId(), "root.root_id");
            requireText(root.getOwnerScopeInstanceId(), "root.owner_scope_instance_id");
            if (!root.getOwnerRouteTag().equals(root.getOwnerRouteTag().strip())) throw invalid("根路由标签格式错误");
            RootScope scope = new RootScope(root.getOwnerNamespace(), root.getOwnerKind(),
                    root.getOwnerScopeInstanceId(), root.getOwnerRouteTag());
            if (!grant.rootScopes().contains(scope)) {
                throw RacingServiceException.failure("FORBIDDEN", "执行服务没有该根归属及原范围的代读权限");
            }
            if (!context.getCallerGenerationId().matches("gen-[0-9a-f]{64}")) {
                throw invalid("业务调用缺少有效的执行服务构建代际");
            }
        }
        return new AuthorizedCall(grant.principal(), context, actual, grant.actions(), grant.readSourceScopes());
    }

    public ConfirmedRuntimeContext confirm(String action, RuntimeCallContext context) {
        AuthorizedCall call = authorize(action, context);
        return ConfirmedRuntimeContext.newBuilder().setContext(call.context())
                .setActualTarget(call.actualTarget()).setServicePrincipal(call.principal()).build();
    }

    private Grant authenticate() {
        // Dubbo 的提供者 ContextFilter 将本次 Invocation 附件放入服务端上下文。
        String keyId = RpcContext.getServerAttachment().getAttachment(KEY_ID_ATTACHMENT);
        String token = RpcContext.getServerAttachment().getAttachment(TOKEN_ATTACHMENT);
        if (keyId == null || keyId.isBlank() || token == null || token.isBlank()) throw unauthenticated();
        List<Grant> grants = grants();
        Grant match = null;
        for (Grant grant : grants) {
            if (grant.keyId().equals(keyId)) match = grant;
        }
        if (match == null || !MessageDigest.isEqual(sha256(token), HexFormat.of().parseHex(match.tokenSha256()))) {
            throw unauthenticated();
        }
        return match;
    }

    private List<Grant> grants() {
        try {
            List<Grant> grants = JSON.readValue(properties.serviceGrantsJson(),
                    JSON.getTypeFactory().constructCollectionType(List.class, Grant.class));
            if (grants == null) throw new IllegalArgumentException();
            Set<String> keys = new HashSet<>();
            for (Grant grant : grants) {
                if (grant == null || !text(grant.keyId()) || !keys.add(grant.keyId())
                        || !text(grant.principal()) || !text(grant.environment())
                        || !text(grant.targetScopeInstanceId()) || grant.tokenSha256() == null
                        || !grant.tokenSha256().matches("[0-9a-f]{64}") || grant.actions() == null
                        || grant.actions().isEmpty() || grant.actions().stream().anyMatch(a -> !text(a) || a.contains("*"))
                        || grant.rootScopes() == null || grant.readSourceScopes() == null) {
                    throw new IllegalArgumentException();
                }
                for (RootScope root : grant.rootScopes()) {
                    if (root == null || !text(root.ownerNamespace()) || !text(root.ownerKind())
                            || !text(root.ownerScopeInstanceId()) || root.ownerRouteTag() == null
                            || !root.ownerRouteTag().equals(root.ownerRouteTag().strip())) throw new IllegalArgumentException();
                }
                for (AuthorizedCall.SourceScope source : grant.readSourceScopes()) {
                    if (source == null || !text(source.scopeInstanceId())
                            || !grant.environment().equals(source.environment())) throw new IllegalArgumentException();
                }
            }
            return grants;
        } catch (RacingServiceException ex) {
            throw ex;
        } catch (Exception ex) {
            // 解析器异常可能包含配置正文，不能把原异常或凭据内容带入回复和日志。
            throw RacingServiceException.failure("SCOPE_UNAVAILABLE", "实验平台服务授权配置格式错误");
        }
    }

    private static byte[] sha256(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("Java 运行环境缺少 SHA-256");
        }
    }

    private static boolean text(String value) {
        return value != null && !value.isBlank() && value.equals(value.strip());
    }

    private static void requireText(String value, String field) {
        if (!text(value)) throw invalid("字段缺失或格式错误：" + field);
    }

    private static RacingServiceException invalid(String message) {
        return RacingServiceException.failure("INVALID_ARGUMENT", message);
    }

    private static RacingServiceException unauthenticated() {
        return RacingServiceException.failure("UNAUTHENTICATED", "服务调用凭据缺失或无效");
    }

    private record RootScope(String ownerNamespace, String ownerKind,
            String ownerScopeInstanceId, String ownerRouteTag) { }

    private record Grant(String keyId, String tokenSha256, String principal, String environment,
            String targetScopeInstanceId, Set<String> actions, Set<RootScope> rootScopes,
            Set<AuthorizedCall.SourceScope> readSourceScopes) {
        @Override
        public String toString() { return "RacingServiceGrant[redacted]"; }
    }
}
