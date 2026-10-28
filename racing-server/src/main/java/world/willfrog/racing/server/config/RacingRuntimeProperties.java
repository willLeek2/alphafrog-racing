package world.willfrog.racing.server.config;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import world.willfrog.racing.idl.RuntimeIdentity;
import world.willfrog.racing.server.security.RacingServiceException;

/** 从部署配置取得本实例身份；业务调用时检查，保持问候接口的独立启动能力。 */
@Component
public final class RacingRuntimeProperties {
    private final Environment environment;

    public RacingRuntimeProperties(Environment environment) {
        this.environment = environment;
    }

    public RuntimeIdentity currentIdentity() {
        String generation = required("AF_DEPLOYMENT_GENERATION_ID");
        if (!generation.matches("gen-[0-9a-f]{64}")) throw unavailable("AF_DEPLOYMENT_GENERATION_ID");
        String route = environment.getProperty("AF_LANE_TRAFFIC_SCOPE_ID");
        if (route == null || route.isBlank()) route = present("AF_LANE_TAG");
        if (!route.equals(route.strip())) throw unavailable("AF_LANE_TRAFFIC_SCOPE_ID");
        return RuntimeIdentity.newBuilder()
                .setEnvironment(required("RACING_ENVIRONMENT"))
                .setScopeInstanceId(required("AF_LANE_CREATION_ID"))
                .setRouteTag(route)
                .setDeploymentId(required("AF_DEPLOYMENT_ID"))
                .setActualTargetGenerationId(generation).build();
    }

    public String serviceGrantsJson() {
        return required("RACING_SERVICE_GRANTS_JSON");
    }

    private String required(String key) {
        String value = present(key);
        if (value.isBlank()) throw unavailable(key);
        return value;
    }

    private String present(String key) {
        String value = environment.getProperty(key);
        if (value == null || !value.equals(value.strip())) throw unavailable(key);
        return value;
    }

    private static RacingServiceException unavailable(String key) {
        return RacingServiceException.failure("SCOPE_UNAVAILABLE", "实验平台部署配置缺失或格式错误：" + key);
    }
}
