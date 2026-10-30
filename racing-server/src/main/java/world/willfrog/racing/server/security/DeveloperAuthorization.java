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
import world.willfrog.racing.server.config.RacingRuntimeProperties;

/** 核对开发者钥匙和原实例范围。入口传入动作名，不与当前进程身份比较。 */
@Component
public final class DeveloperAuthorization {
    public static final String KEY_ID_ATTACHMENT = "racing.developer-key-id";
    public static final String TOKEN_ATTACHMENT = "racing.developer-token";
    public static final String READ_ASSIGNMENT_HISTORY = "ReadAssignmentHistory";
    public static final String READ_PROMPT_SET_VERSION_HISTORY = "ReadPromptSetVersionHistory";
    public static final String READ_PROMPT_VERSION_HISTORY = "ReadPromptVersionHistory";
    public static final String QUERY_EXPERIMENT_RESULT = "QueryExperimentResult";
    public static final String SUPPLEMENT_EXPERIMENT_RESULT = "SupplementExperimentResult";
    public static final String CORRECT_EXPERIMENT_RESULT = "CorrectExperimentResult";
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final RacingRuntimeProperties properties;

    public DeveloperAuthorization(RacingRuntimeProperties properties) {
        this.properties = properties;
    }

    public AuthorizedDeveloper authorize(String action, String environment, String scopeInstanceId) {
        if (!text(action) || action.contains("*")) throw invalid("开发者动作缺失或格式错误");
        if (!text(environment) || !text(scopeInstanceId)) throw invalid("历史读取缺少环境或原实例编号");
        Grant grant = authenticate();
        if (!grant.actions().contains(action) || !grant.environment().equals(environment)
                || !grant.scopeInstanceIds().contains(scopeInstanceId)) {
            throw RacingServiceException.failure("FORBIDDEN", "开发者没有该原实例的历史访问权限");
        }
        return new AuthorizedDeveloper(grant.principal(), environment, scopeInstanceId);
    }

    private Grant authenticate() {
        String keyId = RpcContext.getServerAttachment().getAttachment(KEY_ID_ATTACHMENT);
        String token = RpcContext.getServerAttachment().getAttachment(TOKEN_ATTACHMENT);
        if (keyId == null || keyId.isBlank() || token == null || token.isBlank()) throw unauthenticated();
        Grant match = null;
        for (Grant grant : grants()) {
            if (grant.keyId().equals(keyId)) match = grant;
        }
        if (match == null) throw unauthenticated();
        byte[] expected;
        try {
            expected = HexFormat.of().parseHex(match.tokenSha256());
        } catch (IllegalArgumentException ex) {
            throw unauthenticated();
        }
        if (!MessageDigest.isEqual(sha256(token), expected)) throw unauthenticated();
        return match;
    }

    private List<Grant> grants() {
        try {
            List<Grant> grants = JSON.readValue(properties.developerGrantsJson(),
                    JSON.getTypeFactory().constructCollectionType(List.class, Grant.class));
            if (grants == null) throw new IllegalArgumentException();
            Set<String> keys = new HashSet<>();
            for (Grant grant : grants) {
                if (grant == null || !text(grant.keyId()) || !keys.add(grant.keyId())
                        || !text(grant.principal()) || !text(grant.environment())
                        || grant.tokenSha256() == null || !grant.tokenSha256().matches("[0-9a-f]{64}")
                        || grant.scopeInstanceIds() == null || grant.scopeInstanceIds().isEmpty()
                        || grant.scopeInstanceIds().stream().anyMatch(id -> !text(id))
                        || grant.actions() == null || grant.actions().isEmpty()
                        || grant.actions().stream().anyMatch(action -> !text(action) || action.contains("*"))) {
                    throw new IllegalArgumentException();
                }
            }
            return grants;
        } catch (RacingServiceException ex) {
            throw ex;
        } catch (Exception ex) {
            throw RacingServiceException.failure("SCOPE_UNAVAILABLE", "实验平台开发者授权配置格式错误");
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

    private static RacingServiceException invalid(String message) {
        return RacingServiceException.failure("INVALID_ARGUMENT", message);
    }

    private static RacingServiceException unauthenticated() {
        return RacingServiceException.failure("UNAUTHENTICATED", "开发者调用凭据缺失或无效");
    }

    private record Grant(String keyId, String tokenSha256, String principal, String environment,
            Set<String> scopeInstanceIds, Set<String> actions) {
        @Override
        public String toString() { return "RacingDeveloperGrant[redacted]"; }
    }
}
