package world.willfrog.racing.server.configuration;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.util.JsonFormat;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import world.willfrog.racing.idl.*;

/** 使用公共 MyBatis 会话和事务；JSON 中的候选原文作为字符串保存，不重新格式化候选值。 */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class MyBatisExperimentConfigurationRepository implements ExperimentConfigurationRepository {
    private final ExperimentConfigurationMapper mapper;

    public MyBatisExperimentConfigurationRepository(ExperimentConfigurationMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public ExperimentRecord find(ExperimentScope scope, String experimentId) {
        return record(mapper.find(scope, experimentId));
    }

    @Override
    public ExperimentRecord lockForUpdate(ExperimentScope scope, String experimentId) {
        return record(mapper.lockForUpdate(scope, experimentId));
    }

    @Override
    public ExperimentRecord lockForShare(ExperimentScope scope, String experimentId) {
        return record(mapper.lockForShare(scope, experimentId));
    }

    @Override
    public void insert(ExperimentRecord record) {
        try {
            mapper.insert(row(record));
        } catch (DuplicateKeyException ex) {
            throw ExperimentConfigurationException.failure("ALREADY_EXISTS", "当前范围已有同名实验");
        }
    }

    @Override
    public void updateWithRevision(ExperimentRecord record, long previousRevision) {
        Map<String, Object> row = row(record);
        row.put("previous_revision", previousRevision);
        try {
            if (mapper.update(row) != 1)
                throw ExperimentConfigurationException.failure("REVISION_CONFLICT", "实验已被其他操作修改，请重新读取");
        } catch (DuplicateKeyException ex) {
            throw ExperimentConfigurationException.failure("ALREADY_EXISTS", "当前范围已有同名实验");
        }
    }

    @Override
    public void insertVersion(ExperimentScope scope, PublishedExperimentConfiguration version) {
        Map<String, Object> row = key(scope, version.getExperimentId());
        row.put("version", version.getVersion());
        row.put("configuration_json", json(version.getConfiguration()));
        row.put("total_weight", version.getTotalWeight());
        row.put("published_by", version.getPublishedBy());
        row.put("published_at", version.getPublishedAt());
        mapper.insertVersion(row);
    }

    @Override
    public void appendOperation(ExperimentScope scope, ExperimentOperation operation) {
        Map<String, Object> row = key(scope, operation.getExperimentId());
        row.put("operation_id", operation.getOperationId());
        row.put("action", operation.getAction());
        row.put("actor", operation.getActor());
        row.put("before_revision", operation.getBeforeRevision());
        row.put("after_revision", operation.getAfterRevision());
        row.put("configuration_version", operation.hasConfigurationVersion() ? operation.getConfigurationVersion() : null);
        row.put("detail_json", json(operation));
        row.put("operated_at", operation.getOperatedAt());
        mapper.appendOperation(row);
    }

    @Override
    public List<ExperimentRecord> list(ExperimentScope scope, List<ExperimentStatus> statuses,
            boolean includeDeleted, int offset, int limit) {
        return mapper.list(scope, statuses.stream().map(Enum::name).toList(), includeDeleted, offset, limit)
                .stream().map(this::record).toList();
    }

    @Override
    public List<PublishedExperimentConfiguration> listVersions(ExperimentScope scope, String experimentId,
            int offset, int limit) {
        return mapper.listVersions(scope, experimentId, offset, limit).stream().map(this::version).toList();
    }

    @Override
    public PublishedExperimentConfiguration findVersion(ExperimentScope scope, String experimentId, long version) {
        Map<String, Object> row = mapper.findVersion(scope, experimentId, version);
        return row == null ? null : version(row);
    }

    @Override
    public List<ExperimentOperation> listOperations(ExperimentScope scope, String experimentId, int offset, int limit) {
        return mapper.listOperations(scope, experimentId, offset, limit).stream()
                .map(json -> parse(json, ExperimentOperation.newBuilder()).build()).toList();
    }

    private ExperimentRecord record(Map<String, Object> row) {
        if (row == null) return null;
        ExperimentScope scope = ExperimentScope.newBuilder().setEnvironment(text(row, "environment"))
                .setScopeInstanceId(text(row, "scope_instance_id")).build();
        ExperimentRecord.Builder record = ExperimentRecord.newBuilder()
                .setExperimentId(text(row, "experiment_id")).setScope(scope)
                .setStatus(ExperimentStatus.valueOf(text(row, "status"))).setRevision(number(row, "revision"))
                .setDraftRevision(number(row, "draft_revision"))
                .setDraft(parse(text(row, "draft_json"), ExperimentConfiguration.newBuilder()))
                .setCreatedAt(time(row, "created_at")).setUpdatedAt(time(row, "updated_at"));
        if (row.get("published_version") != null) {
            Map<String, Object> published = mapper.findVersion(scope, record.getExperimentId(), number(row, "published_version"));
            if (published == null) throw storageFailure();
            record.setPublished(version(published));
        }
        if (row.get("final_json") != null)
            record.setFinalRecord(parse(text(row, "final_json"), ExperimentFinalRecord.newBuilder()));
        if (row.get("deleted_at") != null) record.setDeletedAt(time(row, "deleted_at"));
        return record.build();
    }

    private PublishedExperimentConfiguration version(Map<String, Object> row) {
        return PublishedExperimentConfiguration.newBuilder().setExperimentId(text(row, "experiment_id"))
                .setVersion(number(row, "version"))
                .setConfiguration(parse(text(row, "configuration_json"), ExperimentConfiguration.newBuilder()))
                .setTotalWeight(number(row, "total_weight")).setPublishedBy(text(row, "published_by"))
                .setPublishedAt(time(row, "published_at")).build();
    }

    private static Map<String, Object> row(ExperimentRecord record) {
        Map<String, Object> row = key(record.getScope(), record.getExperimentId());
        row.put("name", record.getDraft().getName().isBlank() ? null : record.getDraft().getName());
        row.put("status", record.getStatus().name());
        row.put("revision", record.getRevision());
        row.put("draft_revision", record.getDraftRevision());
        row.put("draft_json", json(record.getDraft()));
        row.put("published_version", record.hasPublished() ? record.getPublished().getVersion() : null);
        row.put("final_json", record.hasFinalRecord() ? json(record.getFinalRecord()) : null);
        row.put("deleted_at", record.getDeletedAt().isEmpty() ? null : record.getDeletedAt());
        row.put("created_at", record.getCreatedAt());
        row.put("updated_at", record.getUpdatedAt());
        return row;
    }

    private static Map<String, Object> key(ExperimentScope scope, String experimentId) {
        Map<String, Object> row = new HashMap<>();
        row.put("environment", scope.getEnvironment());
        row.put("scope_instance_id", scope.getScopeInstanceId());
        row.put("experiment_id", experimentId);
        return row;
    }

    private static String text(Map<String, Object> row, String key) {
        return (String) row.get(key);
    }

    private static long number(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }

    private static String time(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value instanceof Timestamp timestamp) return timestamp.toInstant().toString();
        if (value instanceof OffsetDateTime time) return time.toInstant().toString();
        throw storageFailure();
    }

    private static String json(Message value) {
        try {
            return JsonFormat.printer().omittingInsignificantWhitespace().print(value);
        } catch (InvalidProtocolBufferException ex) {
            throw storageFailure();
        }
    }

    private static <T extends Message.Builder> T parse(String json, T builder) {
        try {
            JsonFormat.parser().merge(json, builder);
            return builder;
        } catch (InvalidProtocolBufferException ex) {
            throw storageFailure();
        }
    }

    private static ExperimentConfigurationException storageFailure() {
        return ExperimentConfigurationException.failure("STORAGE_ERROR", "实验配置存储内容无法读取");
    }
}
