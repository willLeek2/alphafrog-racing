package world.willfrog.racing.server.usage;

import org.springframework.stereotype.Repository;
import world.willfrog.racing.server.security.RacingServiceException;

/** 先插入后锁读，避免并发的同一次上报各自生成使用行编号。 */
@Repository
public class ExperimentUsageRepository {
    private final ExperimentUsageMapper mapper;

    public ExperimentUsageRepository(ExperimentUsageMapper mapper) {
        this.mapper = mapper;
    }

    public ExperimentUsageMapper.UsageRow insertAndLock(ExperimentUsageMapper.UsageInsert row) {
        mapper.insertIfAbsent(row);
        ExperimentUsageMapper.UsageRow locked = mapper.lock(row.servicePrincipal(), row.experimentId(),
                row.usageAttemptId());
        if (locked == null) {
            throw RacingServiceException.failure("PERSISTENCE_UNCONFIRMED", "使用记录尚未确认保存，请用同一次上报重试");
        }
        return locked;
    }

    public ExperimentUsageMapper.UsageRow lock(String principal, String experimentId, String attemptId) {
        return mapper.lock(principal, experimentId, attemptId);
    }

    public ExperimentUsageMapper.UsageRow find(String principal, String experimentId, String attemptId) {
        return mapper.find(principal, experimentId, attemptId);
    }

    public ExperimentUsageMapper.FeedbackRow findFeedback(String usageId) {
        return mapper.findFeedback(usageId);
    }

    public void insertFeedbackIfAbsent(String feedbackId, String usageId, String resultJson) {
        mapper.insertFeedbackIfAbsent(feedbackId, usageId, resultJson);
    }
}
