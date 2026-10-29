package world.willfrog.racing.server.evaluation;

import java.util.UUID;
import org.springframework.stereotype.Repository;
import world.willfrog.racing.idl.EvaluationValue;
import world.willfrog.racing.idl.ValueSource;
import world.willfrog.racing.idl.ValueSourceKind;

/** 分配正文保持原始JSON表示；没有版本和候选的默认分配保持缺省字段。 */
@Repository
public class ExperimentAssignmentRepository {
    private final ExperimentAssignmentMapper mapper;

    public ExperimentAssignmentRepository(ExperimentAssignmentMapper mapper) { this.mapper = mapper; }

    public EvaluationValue find(ExperimentBucket.EvaluationKey key) {
        var record = mapper.find(key);
        if (record == null) return null;
        var source = ValueSource.newBuilder().setKind(ValueSourceKind.valueOf(record.sourceKind()))
                .setConfigurationScopeInstanceId(key.targetScopeInstanceId());
        if (record.configurationVersion() != null) source.setConfigurationVersion(record.configurationVersion());
        if (record.candidateId() != null) source.setCandidateId(record.candidateId());
        if (record.businessDefaultSourceJson() != null) source.setBusinessDefaultSourceJson(record.businessDefaultSourceJson());
        return EvaluationValue.newBuilder().setAssignmentId(record.assignmentId())
                .setValueJson(record.valueJson()).setSource(source).build();
    }

    public void insertIfAbsent(ExperimentBucket.EvaluationKey key,
            ExperimentValueDecision.Decision decision, String principal) {
        var source = decision.source();
        mapper.insertIfAbsent(key, new ExperimentAssignmentMapper.Assignment(UUID.randomUUID().toString(),
                decision.valueJson(), source.hasConfigurationVersion() ? source.getConfigurationVersion() : null,
                source.hasCandidateId() ? source.getCandidateId() : null, source.getKind().name(),
                source.getKind() == ValueSourceKind.ORDINARY_DEFAULT ? source.getBusinessDefaultSourceJson() : null), principal);
    }
}
