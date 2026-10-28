package world.willfrog.racing.server.configuration;

import java.util.List;
import world.willfrog.racing.idl.FieldViolation;
import world.willfrog.racing.idl.RacingError;

/** 保留配置业务原因，由统一入口转换为互斥的错误回复。 */
public final class ExperimentConfigurationException extends RuntimeException {
    private final RacingError error;

    public ExperimentConfigurationException(RacingError error) {
        super(error.getMessage());
        this.error = error;
    }

    public RacingError error() {
        return error;
    }

    public static ExperimentConfigurationException invalid(List<FieldViolation> violations) {
        return new ExperimentConfigurationException(RacingError.newBuilder()
                .setCode("INVALID_ARGUMENT").setMessage("实验配置未通过检查")
                .addAllFieldViolations(violations).build());
    }

    public static ExperimentConfigurationException failure(String code, String message) {
        return new ExperimentConfigurationException(RacingError.newBuilder()
                .setCode(code).setMessage(message).build());
    }
}
