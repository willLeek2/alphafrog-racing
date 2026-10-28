package world.willfrog.racing.server.security;

import java.util.Set;
import world.willfrog.racing.idl.RuntimeCallContext;
import world.willfrog.racing.idl.RuntimeIdentity;

/** 由公共授权检查产生，业务使用该执行主体填写操作记录。 */
public final class AuthorizedCall {
    private final String principal;
    private final RuntimeCallContext context;
    private final RuntimeIdentity actualTarget;
    private final Set<String> actions;
    private final Set<SourceScope> readSourceScopes;

    AuthorizedCall(String principal, RuntimeCallContext context, RuntimeIdentity actualTarget,
            Set<String> actions, Set<SourceScope> readSourceScopes) {
        this.principal = principal;
        this.context = context;
        this.actualTarget = actualTarget;
        this.actions = Set.copyOf(actions);
        this.readSourceScopes = Set.copyOf(readSourceScopes);
    }

    public String principal() { return principal; }
    public RuntimeCallContext context() { return context; }
    public RuntimeIdentity actualTarget() { return actualTarget; }

    /** 结束实验时，离线就绪证明只接受另外授权的执行主体提交。 */
    public boolean maySubmitOfflineReadiness() {
        return actions.contains("SubmitOfflineReadiness");
    }

    /** 版本自己的读取授权仍由提示词服务核对；本方法仅核对来源范围。 */
    public void requireReadableSource(String environment, String scopeInstanceId) {
        boolean ownScope = actualTarget.getEnvironment().equals(environment)
                && actualTarget.getScopeInstanceId().equals(scopeInstanceId);
        boolean sharedScope = actualTarget.getEnvironment().equals(environment)
                && readSourceScopes.contains(new SourceScope(environment, scopeInstanceId));
        if (!ownScope && !sharedScope) {
            throw RacingServiceException.failure("FORBIDDEN", "执行服务没有读取该来源范围的权限");
        }
    }

    public record SourceScope(String environment, String scopeInstanceId) { }
}
