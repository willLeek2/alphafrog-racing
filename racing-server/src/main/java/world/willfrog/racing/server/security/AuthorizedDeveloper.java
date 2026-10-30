package world.willfrog.racing.server.security;

/** 开发者历史读取通过后的身份和获准范围。 */
public final class AuthorizedDeveloper {
    private final String principal;
    private final String environment;
    private final String scopeInstanceId;

    AuthorizedDeveloper(String principal, String environment, String scopeInstanceId) {
        this.principal = principal;
        this.environment = environment;
        this.scopeInstanceId = scopeInstanceId;
    }

    public String principal() { return principal; }
    public String environment() { return environment; }
    public String scopeInstanceId() { return scopeInstanceId; }

    @Override
    public String toString() { return "AuthorizedDeveloper[redacted]"; }
}
