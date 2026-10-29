package world.willfrog.racing.server.prompt;

import com.google.protobuf.Message;
import com.google.protobuf.Timestamp;
import com.google.protobuf.util.JsonFormat;
import java.time.Instant;
import java.util.UUID;
import world.willfrog.racing.idl.*;
import world.willfrog.racing.server.security.AuthorizedCall;

/**
 * 业务范围、修订与序列化的共同检查，不读取外部配置或凭据。
 */
final class PromptSupport {

    private PromptSupport() {
    }

    static PromptBusinessException error(String code, String message) {
        return new PromptBusinessException(RacingError.newBuilder()
                                                      .setCode(code)
                                                      .setMessage(message)
                                                      .build());
    }

    static void require(boolean value, String code, String message) {
        if (!value) {
            throw error(code, message);
        }
    }

    static void text(String value, String field) {
        require(value != null && !value.isBlank() && value.indexOf('\0') < 0, "INVALID_ARGUMENT", "需要提供有效字段：" + field);
    }

    static void id(String value) {
        try {
            require(UUID.fromString(value).toString().equals(value), "INVALID_ARGUMENT", "资源编号必须是UUID");
        } catch (IllegalArgumentException e) {
            throw error("INVALID_ARGUMENT", "资源编号必须是UUID");
        }
    }

    static void scope(PromptScope scope) {
        text(scope.getEnvironment(), "environment");
        text(scope.getScopeInstanceId(), "scope_instance_id");
    }

    static PromptScope own(AuthorizedCall call) {
        return PromptScope.newBuilder()
                          .setEnvironment(call.actualTarget().getEnvironment())
                          .setScopeInstanceId(call.actualTarget().getScopeInstanceId())
                          .build();
    }

    static void write(AuthorizedCall call, PromptScope scope) {
        scope(scope);
        require(own(call).equals(scope), "FORBIDDEN", "只能修改当前授权目标范围的提示词资源");
    }

    static void read(AuthorizedCall call, PromptScope scope) {
        scope(scope);
        call.requireReadableSource(scope.getEnvironment(), scope.getScopeInstanceId());
    }

    static void revision(long actual, long expected) {
        require(actual == expected, "REVISION_CONFLICT", "内容已变化，请刷新后重新提交");
    }

    static Timestamp now() {
        Instant t = Instant.now();
        return Timestamp.newBuilder()
                        .setSeconds(t.getEpochSecond())
                        .setNanos(t.getNano())
                        .build();
    }

    static String uuid() {
        return UUID.randomUUID()
                   .toString();
    }

    static PromptCreationInfo creation(AuthorizedCall call) {
        return PromptCreationInfo.newBuilder()
                                 .setPrincipal(call.principal())
                                 .setCreatedAt(now())
                                 .build();
    }

    static PromptModificationInfo modification(AuthorizedCall call, String action) {
        return PromptModificationInfo.newBuilder()
                                     .setPrincipal(call.principal())
                                     .setModifiedAt(now())
                                     .setAction(action)
                                     .build();
    }

    static PromptTargetRef target(PromptVersionRef r) {
        return PromptTargetRef.newBuilder()
                              .setPromptVersion(r)
                              .build();
    }

    static PromptTargetRef target(PromptSetVersionRef r) {
        return PromptTargetRef.newBuilder()
                              .setPromptSetVersion(r)
                              .build();
    }

    static PromptScope scope(PromptTargetRef r) {
        return switch(r.getTargetCase()) {
            case PROMPT_VERSION ->
                r.getPromptVersion()
                 .getScope();
            case PROMPT_SET_VERSION ->
                r.getPromptSetVersion()
                 .getScope();
            default ->
                throw error("INVALID_ARGUMENT", "需要明确引用版本");
        };
    }

    static String targetKey(PromptTargetRef r) {
        scope(scope(r));
        String id;
        long version;
        if (r.hasPromptVersion()) {
            id = r.getPromptVersion()
                  .getPromptId();
            version = r.getPromptVersion()
                       .getVersion();
        } else {
            id = r.getPromptSetVersion()
                  .getPromptSetId();
            version = r.getPromptSetVersion()
                       .getVersion();
        }
        id(id);
        require(version > 0, "INVALID_ARGUMENT", "版本必须大于0");
        return r.getTargetCase() + "/" + scope(r).getEnvironment() + "/" + scope(r).getScopeInstanceId() + "/" + id + "/" + version;
    }

    static String json(Message value) {
        try {
            return JsonFormat.printer()
                             .omittingInsignificantWhitespace()
                             .print(value);
        } catch (Exception e) {
            throw error("STORAGE_ERROR", "提示词记录无法编码");
        }
    }

    static <T extends Message.Builder> T parse(String json, T builder) {
        try {
            JsonFormat.parser()
                      .merge(json, builder);
            return builder;
        } catch (Exception e) {
            throw error("STORAGE_ERROR", "提示词持久记录无法读取");
        }
    }

    static Page page(PromptPageRequest p) {
        int number = p.getPageNumber() == 0 ? 1 : p.getPageNumber(), size = p.getPageSize() == 0 ? 20 : p.getPageSize();
        require(number > 0 && size > 0, "INVALID_ARGUMENT", "页码和页大小必须大于0");
        size = Math.min(size, 100);
        long offset = (long) (number - 1) * size;
        require(offset <= Integer.MAX_VALUE, "INVALID_ARGUMENT", "页码超过查询范围");
        return new Page(number, size, (int) offset);
    }

    record Page(int number, int size, int offset) {

        PromptPageInfo info(long total) {
            return PromptPageInfo.newBuilder()
                                 .setPageNumber(number)
                                 .setPageSize(size)
                                 .setTotalCount(total)
                                 .build();
        }
    }
}
