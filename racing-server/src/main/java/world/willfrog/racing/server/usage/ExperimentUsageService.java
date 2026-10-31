package world.willfrog.racing.server.usage;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;
import world.willfrog.racing.idl.EvaluationValue;
import world.willfrog.racing.idl.ExperimentScope;
import world.willfrog.racing.idl.ExperimentStatus;
import world.willfrog.racing.idl.ExperimentUsageReceipt;
import world.willfrog.racing.idl.RecordExperimentUsageRequest;
import world.willfrog.racing.idl.ValueSource;
import world.willfrog.racing.idl.ValueSourceKind;
import world.willfrog.racing.server.configuration.ExperimentConfigurationException;
import world.willfrog.racing.server.configuration.ExperimentConfigurationService;
import world.willfrog.racing.server.evaluation.ExperimentAssignmentRepository;
import world.willfrog.racing.server.evaluation.ExperimentBucket;
import world.willfrog.racing.server.security.AuthorizedCall;
import world.willfrog.racing.server.security.RacingServiceException;

/** 同一次上报只保留先写入的使用事实；提交后再独立读回，避免把未落库的结果当成已保存。 */
@Service
public final class ExperimentUsageService {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final ExperimentUsageRepository usages;
    private final ExperimentAssignmentRepository assignments;
    private final ExperimentConfigurationService configurations;
    private final TransactionTemplate write;
    private final TransactionTemplate read;

    public ExperimentUsageService(ExperimentUsageRepository usages, ExperimentAssignmentRepository assignments,
            ExperimentConfigurationService configurations, PlatformTransactionManager transactions) {
        this.usages = usages;
        this.assignments = assignments;
        this.configurations = configurations;
        this.write = new TransactionTemplate(transactions);
        this.write.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.read = new TransactionTemplate(transactions);
        this.read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.read.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public ExperimentUsageReceipt record(AuthorizedCall call, RecordExperimentUsageRequest request) {
        RequestFacts facts = requestFacts(call, request);
        try {
            write.executeWithoutResult(status -> save(call, facts));
        } catch (DataAccessException | TransactionException failure) {
            return readBackOrUnconfirmed(call, facts);
        }
        return readBackOrUnconfirmed(call, facts);
    }

    private void save(AuthorizedCall call, RequestFacts facts) {
        var existing = usages.lock(facts.principal(), facts.key().experimentId(), facts.attemptId());
        Expected expected = derive(facts, existing);
        Snapshot snapshot = snapshot(facts, expected, existing);
        if (existing == null) {
            existing = usages.insertAndLock(snapshot.insert());
        }
        if (!same(existing, snapshot)) {
            throw conflict(existing.usageId());
        }
        storeFeedback(existing, facts);
    }

    private ExperimentUsageReceipt readBackOrUnconfirmed(AuthorizedCall call, RequestFacts facts) {
        try {
            ExperimentUsageReceipt confirmed = read.execute(status -> readBack(call, facts));
            if (confirmed != null) {
                return confirmed;
            }
        } catch (DataAccessException | TransactionException ignored) {
            // 读回本身失败时不编造使用行编号。
        }
        throw unconfirmed();
    }

    private ExperimentUsageReceipt readBack(AuthorizedCall call, RequestFacts facts) {
        var row = usages.find(facts.principal(), facts.key().experimentId(), facts.attemptId());
        if (row == null) {
            return null;
        }
        Expected expected;
        Snapshot snapshot;
        try {
            expected = derive(facts, row);
            snapshot = snapshot(facts, expected, row);
        } catch (RacingServiceException | ExperimentConfigurationException failure) {
            return null;
        }
        if (!same(row, snapshot)) {
            return null;
        }
        var feedback = usages.findFeedback(row.usageId());
        if (facts.result() != null) {
            if (feedback == null || !sameJson(parseJson(feedback.resultJson()), facts.result())) {
                return null;
            }
        }
        return ExperimentUsageReceipt.newBuilder().setUsageId(row.usageId())
                .setUsageAttemptId(row.usageAttemptId())
                .setFeedbackId(feedback == null ? "" : feedback.feedbackId()).build();
    }

    private void storeFeedback(ExperimentUsageMapper.UsageRow row, RequestFacts facts) {
        if (facts.result() == null) {
            return;
        }
        var existing = usages.findFeedback(row.usageId());
        if (existing == null) {
            usages.insertFeedbackIfAbsent(UUID.randomUUID().toString(), row.usageId(), facts.resultJson());
            existing = usages.findFeedback(row.usageId());
            if (existing == null) {
                throw unconfirmed();
            }
        }
        if (!sameJson(parseJson(existing.resultJson()), facts.result())) {
            throw conflict(row.usageId());
        }
    }

    private Expected derive(RequestFacts facts, ExperimentUsageMapper.UsageRow existing) {
        try {
            return derive(facts.key());
        } catch (RacingServiceException failure) {
            if (existing == null) {
                throw failure;
            }
            throw conflict(existing.usageId());
        } catch (ExperimentConfigurationException failure) {
            if (existing == null) {
                throw failure;
            }
            throw conflict(existing.usageId());
        }
    }

    private Expected derive(ExperimentBucket.EvaluationKey key) {
        EvaluationValue assignment = assignments.find(key);
        if (assignment == null) {
            var scope = ExperimentScope.newBuilder().setEnvironment(key.environment())
                    .setScopeInstanceId(key.targetScopeInstanceId()).build();
            var experiment = configurations.lockForEvaluation(scope, key.experimentId());
            assignment = assignments.find(key);
            if (assignment == null) {
                if (experiment.getStatus() != ExperimentStatus.ENDED || !experiment.hasFinalRecord()
                        || experiment.getFinalRecord().getValueJson().isBlank()
                        || experiment.getFinalRecord().getConfigurationVersion() <= 0) {
                    throw RacingServiceException.failure("NOT_FOUND", "当前根没有该实验的分配，实验也尚未以固定值结束");
                }
                return new Expected(null, ValueSourceKind.ENDED_FIXED.name(),
                        experiment.getFinalRecord().getConfigurationVersion(),
                        parseValue(experiment.getFinalRecord().getValueJson()));
            }
        }
        return fromAssignment(assignment);
    }

    private Expected fromAssignment(EvaluationValue value) {
        ValueSource source = value.getSource();
        String kind = source.getKind().name();
        Long version = source.hasConfigurationVersion() ? source.getConfigurationVersion() : null;
        if (version != null && version <= 0) {
            throw unconfirmed();
        }
        if (source.getKind() == ValueSourceKind.EXPERIMENT && (version == null || source.getCandidateId().isBlank())) {
            throw unconfirmed();
        }
        if (source.getKind() != ValueSourceKind.EXPERIMENT && source.getKind() != ValueSourceKind.ORDINARY_DEFAULT) {
            throw unconfirmed();
        }
        String assignmentId = canonicalUuid(value.getAssignmentId(), "已保存分配编号");
        if (assignmentId == null) {
            throw unconfirmed();
        }
        return new Expected(assignmentId, kind, version, parseValue(value.getValueJson()));
    }

    private Snapshot snapshot(RequestFacts facts, Expected expected, ExperimentUsageMapper.UsageRow existing) {
        if (!Objects.equals(facts.assignmentId(), expected.assignmentId())) {
            if (existing == null) {
                throw invalid("分配编号与当前根在该实验上的分配不一致");
            }
            throw conflict(existing.usageId());
        }
        boolean differs = !sameJson(facts.adoptedValue(), expected.value());
        if (differs && facts.differenceReason() == null) {
            throw invalid("采用值与已保存值不同，必须填写差异原因");
        }
        if (!differs && facts.differenceReason() != null) {
            throw invalid("采用值与已保存值相同，不能填写差异原因");
        }
        if (facts.safetyDefault() && facts.safetyDefaultReason() == null) {
            throw invalid("安全默认必须填写原因");
        }
        if (!facts.safetyDefault() && facts.safetyDefaultReason() != null) {
            throw invalid("未使用安全默认时不能填写原因");
        }
        return new Snapshot(facts, expected, differs);
    }

    private static boolean same(ExperimentUsageMapper.UsageRow row, Snapshot snapshot) {
        return row.servicePrincipal().equals(snapshot.facts().principal())
                && row.environment().equals(snapshot.facts().key().environment())
                && row.scopeInstanceId().equals(snapshot.facts().key().targetScopeInstanceId())
                && row.ownerNamespace().equals(snapshot.facts().key().ownerNamespace())
                && row.ownerKind().equals(snapshot.facts().key().ownerKind())
                && row.ownerScopeInstanceId().equals(snapshot.facts().key().ownerScopeInstanceId())
                && row.rootId().equals(snapshot.facts().key().rootId())
                && row.rootRunId().equals(snapshot.facts().key().rootId())
                && row.currentRunId().equals(snapshot.facts().currentRunId())
                && row.callId().equals(snapshot.facts().callId())
                && row.experimentId().equals(snapshot.facts().key().experimentId())
                && canonicalUuid(row.usageAttemptId(), "已保存尝试编号").equals(snapshot.facts().attemptId())
                && Objects.equals(canonicalUuid(row.assignmentId(), "已保存分配编号"), snapshot.expected().assignmentId())
                && row.sourceKind().equals(snapshot.expected().sourceKind())
                && Objects.equals(row.configurationVersion(), snapshot.expected().configurationVersion())
                && row.adoptedValueJson().equals(snapshot.facts().adoptedValueJson())
                && row.differsFromAssignment() == snapshot.differs()
                && Objects.equals(row.differenceReason(), snapshot.facts().differenceReason())
                && row.safetyDefault() == snapshot.facts().safetyDefault()
                && Objects.equals(row.safetyDefaultReason(), snapshot.facts().safetyDefaultReason())
                && Objects.equals(row.contentChecksum(), snapshot.facts().contentChecksum())
                && Objects.equals(row.contentCheckPassed(), snapshot.facts().contentCheckPassed())
                && Objects.equals(row.contentCheckReason(), snapshot.facts().contentCheckReason())
                && micros(row.occurredAt()).equals(snapshot.facts().occurredAt());
    }

    private RequestFacts requestFacts(AuthorizedCall call, RecordExperimentUsageRequest request) {
        if (request == null || !call.context().hasRoot()) {
            throw invalid("使用上报缺少运行上下文");
        }
        var root = call.context().getRoot();
        ExperimentBucket.EvaluationKey key;
        try {
            key = new ExperimentBucket.EvaluationKey(call.actualTarget().getEnvironment(),
                    call.actualTarget().getScopeInstanceId(), root.getOwnerNamespace(), root.getOwnerKind(),
                    root.getOwnerScopeInstanceId(), root.getRootId(), request.getExperimentId());
        } catch (IllegalArgumentException ex) {
            throw invalid("使用上报的根归属或实验编号无效");
        }
        String attemptId = canonicalUuid(request.getUsageAttemptId(), "使用尝试编号");
        if (attemptId == null) {
            throw invalid("使用尝试编号无效");
        }
        String currentRunId = token(call.context().getCurrentRunId(), "当前运行编号");
        String callId = token(call.context().getOperationId(), "调用编号");
        JsonNode adopted = parseValue(request.getAdoptedValueJson());
        JsonNode result = null;
        String resultJson = blankToNull(request.getResultJson());
        if (resultJson != null) {
            result = parseJson(resultJson);
            if (result == null || !result.isObject()) {
                throw invalid("使用结果必须是 JSON 对象");
            }
        }
        Boolean check = request.hasContentCheckPassed() ? request.getContentCheckPassed() : null;
        String checkReason = blankToNull(request.getContentCheckReason());
        if (check == null && checkReason != null) {
            throw invalid("内容校验原因必须和校验结果一起提供");
        }
        if (Boolean.FALSE.equals(check) && checkReason == null) {
            throw invalid("内容校验未通过时必须填写原因");
        }
        return new RequestFacts(call.principal(), key, attemptId, canonicalUuid(request.getAssignmentId(), "分配编号"),
                request.getAdoptedValueJson(), adopted, blankToNull(request.getDifferenceReason()),
                request.getSafetyDefault(), blankToNull(request.getSafetyDefaultReason()),
                blankToNull(request.getContentChecksum()), check, checkReason, resultJson, result,
                micros(request.getOccurredAt()), currentRunId, callId);
    }

    private static JsonNode parseValue(String text) {
        JsonNode node = parseJson(text);
        if (node == null || (!node.isNumber() && !node.isTextual() && !node.isObject())) {
            throw invalid("采用值必须是数字、文字或 JSON 对象");
        }
        return node;
    }

    private static JsonNode parseJson(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try (JsonParser parser = JSON.createParser(text)) {
            JsonNode node = JSON.readTree(parser);
            if (parser.nextToken() != null) {
                throw invalid("JSON 后面还有多余内容");
            }
            return node;
        } catch (RacingServiceException failure) {
            throw failure;
        } catch (Exception failure) {
            throw invalid("JSON 内容无效");
        }
    }

    private static boolean sameJson(JsonNode left, JsonNode right) {
        if (left == null || right == null) {
            return left == right;
        }
        if (left.isNumber() && right.isNumber()) {
            return left.decimalValue().compareTo(right.decimalValue()) == 0;
        }
        if (left.getNodeType() != right.getNodeType()) {
            return false;
        }
        if (left.isObject()) {
            if (left.size() != right.size()) {
                return false;
            }
            var names = left.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                if (!right.has(name) || !sameJson(left.get(name), right.get(name))) {
                    return false;
                }
            }
            return true;
        }
        if (left.isArray()) {
            if (left.size() != right.size()) {
                return false;
            }
            for (int index = 0; index < left.size(); index++) {
                if (!sameJson(left.get(index), right.get(index))) {
                    return false;
                }
            }
            return true;
        }
        return left.equals(right);
    }

    /** 比较和写库都使用截断后的时刻。数据库对更细的秒小数会舍入，直接写入原文本会和这里差一微秒。 */
    private static Instant micros(String text) {
        try {
            return OffsetDateTime.parse(text).toInstant().truncatedTo(ChronoUnit.MICROS);
        } catch (DateTimeParseException ex) {
            throw invalid("发生时间必须是带时区的时间");
        }
    }

    private static String canonicalUuid(String value, String name) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value).toString();
        } catch (IllegalArgumentException ex) {
            throw invalid(name + "格式无效");
        }
    }

    private static String token(String value, String name) {
        if (value == null || value.isBlank() || !value.equals(value.strip())) {
            throw invalid(name + "不能为空或包含首尾空白");
        }
        return value;
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (!value.equals(value.strip())) {
            throw invalid("文本字段不能包含首尾空白");
        }
        return value;
    }

    private static RacingServiceException invalid(String message) {
        return RacingServiceException.failure("INVALID_ARGUMENT", message);
    }

    private static RacingServiceException unconfirmed() {
        return RacingServiceException.failure("PERSISTENCE_UNCONFIRMED", "使用记录尚未确认保存，请用同一次上报重试");
    }

    private static RacingServiceException conflict(String usageId) {
        var error = world.willfrog.racing.idl.RacingError.newBuilder().setCode("CONFLICT")
                .setMessage("同一使用尝试的已保存事实与本次请求不一致");
        if (usageId != null && !usageId.isBlank()) {
            error.putObjectLocator("usage_id", usageId);
        }
        return new RacingServiceException(error.build());
    }

    private record RequestFacts(String principal, ExperimentBucket.EvaluationKey key, String attemptId,
            String assignmentId, String adoptedValueJson, JsonNode adoptedValue, String differenceReason,
            boolean safetyDefault, String safetyDefaultReason, String contentChecksum, Boolean contentCheckPassed,
            String contentCheckReason, String resultJson, JsonNode result, Instant occurredAt,
            String currentRunId, String callId) {
    }

    private record Expected(String assignmentId, String sourceKind, Long configurationVersion, JsonNode value) {
    }

    private record Snapshot(RequestFacts facts, Expected expected, boolean differs) {
        private ExperimentUsageMapper.UsageInsert insert() {
            return new ExperimentUsageMapper.UsageInsert(UUID.randomUUID().toString(), facts.principal(),
                    facts.key().environment(), facts.key().targetScopeInstanceId(), facts.key().ownerNamespace(),
                    facts.key().ownerKind(), facts.key().ownerScopeInstanceId(), facts.key().rootId(),
                    facts.key().rootId(), facts.currentRunId(), facts.callId(), facts.key().experimentId(),
                    facts.attemptId(), expected.assignmentId(), expected.sourceKind(), expected.configurationVersion(),
                    facts.adoptedValueJson(), differs, facts.differenceReason(), facts.safetyDefault(),
                    facts.safetyDefaultReason(), facts.contentChecksum(), facts.contentCheckPassed(),
                    facts.contentCheckReason(), facts.occurredAt().toString());
        }
    }
}
