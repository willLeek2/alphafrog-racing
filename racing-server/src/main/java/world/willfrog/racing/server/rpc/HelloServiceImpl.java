package world.willfrog.racing.server.rpc;

import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import world.willfrog.racing.idl.DubboHelloServiceTriple.HelloServiceImplBase;
import world.willfrog.racing.idl.HelloReply;
import world.willfrog.racing.idl.HelloRequest;

/** 返回问候语和部署身份，用于核对 RPC 请求实际到达的实例。 */
@DubboService
@Service
public class HelloServiceImpl extends HelloServiceImplBase {

    private final String deploymentId;
    private final String trafficScopeId;
    private final String deploymentGenerationId;

    public HelloServiceImpl(
            @Value("${AF_DEPLOYMENT_ID:local}") String deploymentId,
            @Value("${AF_LANE_TRAFFIC_SCOPE_ID:local}") String trafficScopeId,
            @Value("${AF_DEPLOYMENT_GENERATION_ID:}") String deploymentGenerationId) {
        this.deploymentId = deploymentId;
        this.trafficScopeId = trafficScopeId;
        this.deploymentGenerationId = deploymentGenerationId;
    }

    @Override
    public HelloReply sayHello(HelloRequest request) {
        String name = request.getName();
        String message = name.isBlank() ? "Hello World" : "Hello World, " + name;
        return HelloReply.newBuilder()
                .setMessage(message)
                .setDeploymentId(deploymentId)
                .setTrafficScopeId(trafficScopeId)
                .setDeploymentGenerationId(deploymentGenerationId)
                .build();
    }
}
