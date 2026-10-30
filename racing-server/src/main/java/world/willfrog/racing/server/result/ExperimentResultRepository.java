package world.willfrog.racing.server.result;

import java.util.List;
import org.springframework.stereotype.Repository;

/** 查询和改写都落在调用方已经打开的事务里。 */
@Repository
public class ExperimentResultRepository {
    private final ExperimentResultMapper mapper;

    public ExperimentResultRepository(ExperimentResultMapper mapper) {
        this.mapper = mapper;
    }

    public List<ExperimentResultMapper.ResultRow> query(String environment, String scopeInstanceId,
            String experimentId, Long configurationVersion, String adoptedValueJson, int limit, int offset) {
        return mapper.query(environment, scopeInstanceId, experimentId, configurationVersion, adoptedValueJson,
                limit, offset);
    }

    public ExperimentResultMapper.ResultRow lockUsage(String environment, String scopeInstanceId, String usageId) {
        return mapper.lockUsage(environment, scopeInstanceId, usageId);
    }

    public ExperimentResultMapper.FeedbackRow lockFeedback(String usageId) {
        return mapper.lockFeedback(usageId);
    }

    public void insertFeedback(String feedbackId, String usageId, String resultJson) {
        mapper.insertFeedback(feedbackId, usageId, resultJson);
    }

    public void updateFeedback(String usageId, String resultJson) {
        mapper.updateFeedback(usageId, resultJson);
    }

    public void deleteFeedback(String usageId) {
        mapper.deleteFeedback(usageId);
    }

    public List<ExperimentResultMapper.AssignmentCount> countAssignments(String environment, String scopeInstanceId,
            String experimentId, Long configurationVersion) {
        return mapper.countAssignments(environment, scopeInstanceId, experimentId, configurationVersion);
    }

    public List<ExperimentResultMapper.UsageCount> countUsage(String environment, String scopeInstanceId,
            String experimentId, Long configurationVersion, String adoptedValueJson) {
        return mapper.countUsage(environment, scopeInstanceId, experimentId, configurationVersion, adoptedValueJson);
    }
}
