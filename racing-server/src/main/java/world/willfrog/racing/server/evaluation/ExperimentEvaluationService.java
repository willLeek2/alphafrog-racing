package world.willfrog.racing.server.evaluation;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;
import world.willfrog.racing.idl.EvaluateRequest;
import world.willfrog.racing.idl.EvaluationValue;
import world.willfrog.racing.idl.ExperimentScope;
import world.willfrog.racing.server.configuration.ExperimentConfigurationService;
import world.willfrog.racing.server.security.AuthorizedCall;
import world.willfrog.racing.server.security.RacingServiceException;

/** 首次分配提交后才返回；同一根后续调用优先复用已保存的值和来源。 */
@Service
public final class ExperimentEvaluationService {
    private final ExperimentAssignmentRepository assignments;
    private final ExperimentConfigurationService configurations;
    private final ExperimentValueDecision decisions;
    private final TransactionTemplate transaction;

    public ExperimentEvaluationService(ExperimentAssignmentRepository assignments,
            ExperimentConfigurationService configurations, ExperimentValueDecision decisions,
            PlatformTransactionManager transactions) {
        this.assignments = assignments;
        this.configurations = configurations;
        this.decisions = decisions;
        this.transaction = new TransactionTemplate(transactions);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public EvaluationValue evaluate(AuthorizedCall call, EvaluateRequest request) {
        if (request == null || !call.context().hasRoot()) throw invalid();
        ExperimentBucket.EvaluationKey key;
        try {
            var root = call.context().getRoot();
            key = new ExperimentBucket.EvaluationKey(call.actualTarget().getEnvironment(),
                    call.actualTarget().getScopeInstanceId(), root.getOwnerNamespace(), root.getOwnerKind(),
                    root.getOwnerScopeInstanceId(), root.getRootId(), request.getExperimentId());
        } catch (IllegalArgumentException ex) { throw invalid(); }
        try {
            EvaluationValue value = transaction.execute(status -> {
                var existing = assignments.find(key);
                if (existing != null) return existing;
                var scope = ExperimentScope.newBuilder().setEnvironment(key.environment())
                        .setScopeInstanceId(key.targetScopeInstanceId()).build();
                var experiment = configurations.lockForEvaluation(scope, key.experimentId());
                existing = assignments.find(key);
                if (existing != null) return existing;
                var decision = decisions.select(key, experiment, request.getOrdinaryDefaultJson(),
                        request.getOrdinaryDefaultSourceJson());
                if (!decision.requiresAssignment()) {
                    return EvaluationValue.newBuilder().setValueJson(decision.valueJson())
                            .setSource(decision.source()).build();
                }
                assignments.insertIfAbsent(key, decision, call.principal());
                var saved = assignments.find(key);
                if (saved == null) throw unconfirmed();
                return saved;
            });
            return withTarget(value, call);
        } catch (DataAccessException | TransactionException failure) {
            // 原事务已经退出；提交响应丢失时仅独立读回，不重新生成候选。
            try {
                var saved = transaction.execute(status -> assignments.find(key));
                if (saved != null) return withTarget(saved, call);
            } catch (DataAccessException | TransactionException ignored) {
                // 不返回底层SQL、连接信息或未经确认的取值。
            }
            throw unconfirmed();
        }
    }

    private static EvaluationValue withTarget(EvaluationValue value, AuthorizedCall call) {
        if (value == null) throw unconfirmed();
        return value.toBuilder().setActualTarget(call.actualTarget()).build();
    }

    private static RacingServiceException invalid() {
        return RacingServiceException.failure("INVALID_ARGUMENT", "实验取值缺少有效实验编号或根归属");
    }

    private static RacingServiceException unconfirmed() {
        return RacingServiceException.failure("PERSISTENCE_UNCONFIRMED", "无法确认实验分配已经保存，请使用原根身份重试");
    }
}
