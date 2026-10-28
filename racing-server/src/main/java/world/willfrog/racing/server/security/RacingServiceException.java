package world.willfrog.racing.server.security;

import world.willfrog.racing.idl.RacingError;

/** 公共入口保留明确错误原因，不将权限失败转换成可执行的默认值。 */
public final class RacingServiceException extends RuntimeException {
    private final RacingError error;

    public RacingServiceException(RacingError error) {
        super(error.getMessage());
        this.error = error;
    }

    public RacingError error() {
        return error;
    }

    public static RacingServiceException failure(String code, String message) {
        return new RacingServiceException(RacingError.newBuilder()
                .setCode(code).setMessage(message).build());
    }
}
