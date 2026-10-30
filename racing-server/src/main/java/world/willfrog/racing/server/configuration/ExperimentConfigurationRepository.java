package world.willfrog.racing.server.configuration;

import java.util.List;
import world.willfrog.racing.idl.ExperimentOperation;
import world.willfrog.racing.idl.ExperimentRecord;
import world.willfrog.racing.idl.ExperimentScope;
import world.willfrog.racing.idl.ExperimentStatus;
import world.willfrog.racing.idl.PublishedExperimentConfiguration;

/** 数据库实现使用共同数据源；锁和写入均加入业务已有的事务。 */
public interface ExperimentConfigurationRepository {
    ExperimentRecord find(ExperimentScope scope, String experimentId);

    ExperimentRecord lockForUpdate(ExperimentScope scope, String experimentId);

    ExperimentRecord lockForShare(ExperimentScope scope, String experimentId);

    void insert(ExperimentRecord record);

    void updateWithRevision(ExperimentRecord record, long previousRevision);

    void insertVersion(ExperimentScope scope, PublishedExperimentConfiguration version);

    void appendOperation(ExperimentScope scope, ExperimentOperation operation);

    List<ExperimentRecord> list(ExperimentScope scope, List<ExperimentStatus> statuses,
            boolean includeDeleted, int offset, int limit);

    List<PublishedExperimentConfiguration> listVersions(ExperimentScope scope, String experimentId,
            int offset, int limit);

    List<ExperimentOperation> listOperations(ExperimentScope scope, String experimentId, int offset, int limit);
}
