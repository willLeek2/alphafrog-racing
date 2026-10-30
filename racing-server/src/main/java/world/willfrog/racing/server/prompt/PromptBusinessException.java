package world.willfrog.racing.server.prompt;

import java.util.List;
import java.util.Objects;
import world.willfrog.racing.idl.FieldViolation;
import world.willfrog.racing.idl.RacingError;

/** 结构化提示词业务错误；异常消息不包含正文、变量值或底层异常。 */
public final class PromptBusinessException extends RuntimeException {
    private final RacingError error;

    public PromptBusinessException(RacingError error) {
        super("PROMPT_BUSINESS_ERROR");
        this.error = Objects.requireNonNull(error, "error");
    }

    public RacingError error() {
        return error;
    }

    /** 调用方提供静态字段路径和原因，不把待保存的字段值写入错误。 */
    public static PromptBusinessException invalid(List<FieldViolation> violations) {
        return new PromptBusinessException(RacingError.newBuilder()
                .setCode("INVALID_ARGUMENT")
                .setMessage("提示词内容未通过检查")
                .addAllFieldViolations(violations)
                .build());
    }
}
