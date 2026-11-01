package world.willfrog.racing.server.prompt;

import java.util.List;
import java.util.Objects;
import world.willfrog.racing.idl.FieldViolation;
import world.willfrog.racing.idl.RacingError;

/** 提示词业务错误仅保留结构化原因，保护正文及变量。 */
public final class PromptBusinessException extends RuntimeException {
    private final RacingError error;

    public PromptBusinessException(RacingError error) {
        super("PROMPT_BUSINESS_ERROR");
        this.error = Objects.requireNonNull(error, "error");
    }

    public RacingError error() {
        return error;
    }

    /** 调用方提供静态字段路径和原因，待保存值由业务层保管。 */
    public static PromptBusinessException invalid(List<FieldViolation> violations) {
        return new PromptBusinessException(RacingError.newBuilder()
                .setCode("INVALID_ARGUMENT")
                .setMessage("提示词内容未通过检查")
                .addAllFieldViolations(violations)
                .build());
    }
}
