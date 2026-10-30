package world.willfrog.racing.server.input;

import org.springframework.stereotype.Repository;

/** 复用公共数据源和调用方事务，不另建连接或提交。 */
@Repository
public class ModelInputRepository {
    private final ModelInputMapper mapper;

    public ModelInputRepository(ModelInputMapper mapper) { this.mapper = mapper; }

    public ModelInputRow insertAndLock(ModelInputRow row) {
        mapper.insertIfAbsent(row);
        return mapper.lockAttempt(row.servicePrincipal(), row.attemptId());
    }

    public ModelInputRow findById(String recordId) { return mapper.findById(recordId); }

    public ModelInputRow findAttempt(String principal, String attemptId) {
        return mapper.findAttempt(principal, attemptId);
    }
}
