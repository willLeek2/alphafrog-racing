package world.willfrog.racing.server.rpc;

import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.stereotype.Service;
import world.willfrog.racing.idl.ConfirmRuntimeContextReply;
import world.willfrog.racing.idl.ConfirmRuntimeContextRequest;
import world.willfrog.racing.idl.DubboRacingServiceTriple.RacingServiceImplBase;
import world.willfrog.racing.server.security.RacingServiceException;
import world.willfrog.racing.server.security.ServiceAuthorization;

/** 统一远程入口；上下文确认不创建实验分配或提示词选择。 */
@DubboService
@Service
public final class RacingServiceImpl extends RacingServiceImplBase {
    private final ServiceAuthorization authorization;

    public RacingServiceImpl(ServiceAuthorization authorization) {
        this.authorization = authorization;
    }

    @Override
    public ConfirmRuntimeContextReply confirmRuntimeContext(ConfirmRuntimeContextRequest request) {
        try {
            if (request == null || !request.hasContext()) {
                throw RacingServiceException.failure("INVALID_ARGUMENT", "上下文确认请求缺少运行上下文");
            }
            return ConfirmRuntimeContextReply.newBuilder()
                    .setConfirmed(authorization.confirm(request.getAction(), request.getContext())).build();
        } catch (RacingServiceException failure) {
            return ConfirmRuntimeContextReply.newBuilder().setError(failure.error()).build();
        }
    }
}
