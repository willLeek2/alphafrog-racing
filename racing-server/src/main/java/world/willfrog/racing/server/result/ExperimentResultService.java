package world.willfrog.racing.server.result;

import world.willfrog.racing.idl.CorrectExperimentResultRequest;
import world.willfrog.racing.idl.ExperimentResultPage;
import world.willfrog.racing.idl.QueryExperimentResultRequest;
import world.willfrog.racing.idl.SupplementExperimentResultRequest;
import world.willfrog.racing.server.security.RacingServiceException;

/**
 * 查询、补充和修正实验结果。远程入口已经完成开发者授权，这三个方法不读取动作名。
 * 查询分页默认 50 条，最大 200 条，顺序是 recorded_at、call_id。
 * 补充和修正的请求目前只有原环境和原实例。这一版方法体尚未实现，本类也还没有注册为服务。
 */
public class ExperimentResultService {
    public ExperimentResultPage query(QueryExperimentResultRequest request) {
        throw RacingServiceException.failure("SCOPE_UNAVAILABLE", "实验结果查询尚未实现");
    }

    public void supplement(SupplementExperimentResultRequest request) {
        throw RacingServiceException.failure("SCOPE_UNAVAILABLE", "实验结果补充尚未实现");
    }

    public void correct(CorrectExperimentResultRequest request) {
        throw RacingServiceException.failure("SCOPE_UNAVAILABLE", "实验结果修正尚未实现");
    }
}
