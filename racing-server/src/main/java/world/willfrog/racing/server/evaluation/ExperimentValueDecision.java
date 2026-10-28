package world.willfrog.racing.server.evaluation;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;
import world.willfrog.racing.idl.ExperimentRecord;
import world.willfrog.racing.idl.ExperimentStatus;
import world.willfrog.racing.idl.ValueSource;
import world.willfrog.racing.idl.ValueSourceKind;
import world.willfrog.racing.server.configuration.ExperimentValueValidator;
import world.willfrog.racing.server.security.RacingServiceException;

/** 仅为尚无原分配的根计算待保存内容；调用方先授权、锁定配置，再确认实际保存结果。 */
@Component
public final class ExperimentValueDecision {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final ExperimentValueValidator values;
    private final ExperimentBucket bucket = new ExperimentBucket();

    public ExperimentValueDecision(ExperimentValueValidator values) {
        this.values = values;
    }

    /** 配置来自当前事务持有共享行锁的记录；本方法不查询数据库，也不创建分配编号。 */
    public Decision select(ExperimentBucket.EvaluationKey key, ExperimentRecord experiment,
            String ordinaryDefaultJson, String ordinaryDefaultSourceJson) {
        if (key == null) throw failure("INVALID_ARGUMENT", "首次取值缺少完整根身份");
        if (experiment == null || !experiment.getDeletedAt().isEmpty()) {
            throw failure("NOT_FOUND", "实验不存在或已经删除，不能建立新分配");
        }
        if (!experiment.getExperimentId().equals(key.experimentId()) || !experiment.hasScope()
                || !experiment.getScope().getEnvironment().equals(key.environment())
                || !experiment.getScope().getScopeInstanceId().equals(key.targetScopeInstanceId())) {
            throw failure("SCOPE_MISMATCH", "实验配置不属于本次取值的环境和创建范围");
        }
        ValueSource.Builder source = ValueSource.newBuilder()
                .setConfigurationScopeInstanceId(key.targetScopeInstanceId());
        if (experiment.hasPublished()) {
            var published = experiment.getPublished();
            if (published.getVersion() <= 0 || !published.getExperimentId().equals(key.experimentId())) {
                throw failure("INVALID_STATE", "已发布快照缺少明确版本或属于其他实验");
            }
            source.setConfigurationVersion(published.getVersion());
        } else if (experiment.getStatus() != ExperimentStatus.DRAFT) {
            throw failure("INVALID_STATE", "当前实验状态需要已发布配置");
        }
        if (experiment.getStatus() == ExperimentStatus.ENDED) {
            if (!experiment.hasFinalRecord() || experiment.getFinalRecord().getConfigurationVersion()
                    != experiment.getPublished().getVersion()) {
                throw failure("INVALID_STATE", "结束记录与已发布配置版本不一致");
            }
            String value = experiment.getFinalRecord().getValueJson();
            storedJson(value, "结束固定值");
            values.validateValue(value, experiment.getPublished().getConfiguration().getRules(), "final_record.value_json");
            return new Decision(value, source.setKind(ValueSourceKind.ENDED_FIXED).build(), false);
        }
        // 普通默认也是本次请求的类型契约；有发布快照时按该版本规则核对。
        storedJson(ordinaryDefaultJson, "普通默认值");
        if (experiment.hasPublished()) {
            values.validateValue(ordinaryDefaultJson, experiment.getPublished().getConfiguration().getRules(),
                    "ordinary_default_json");
        } else {
            values.validateSupportedValue(ordinaryDefaultJson, "ordinary_default_json");
        }
        return switch (experiment.getStatus()) {
            case DRAFT, PAUSED -> {
                storedJson(ordinaryDefaultSourceJson, "普通默认来源");
                yield new Decision(ordinaryDefaultJson, source.setKind(ValueSourceKind.ORDINARY_DEFAULT)
                        .setBusinessDefaultSourceJson(ordinaryDefaultSourceJson).build(), true);
            }
            case RUNNING -> {
                var configuration = experiment.getPublished().getConfiguration();
                values.validatePublished(configuration);
                var first = configuration.getCandidateA();
                var second = configuration.getCandidateB();
                var selected = bucket.select(key,
                        new ExperimentBucket.Candidate(first.getCandidateId(), first.getValueJson(), BigDecimal.valueOf(first.getWeight())),
                        new ExperimentBucket.Candidate(second.getCandidateId(), second.getValueJson(), BigDecimal.valueOf(second.getWeight())));
                storedJson(selected.valueJson(), "实验候选值");
                yield new Decision(selected.valueJson(), source.setKind(ValueSourceKind.EXPERIMENT)
                        .setCandidateId(selected.id()).build(), true);
            }
            default -> throw failure("INVALID_STATE", "实验状态不支持首次取值");
        };
    }

    /** TEXT与JSONB均须无损保存；拒绝转义后形成的NUL和孤立代理字符，原文保持。 */
    private static void storedJson(String text, String field) {
        try {
            requireStoredText(text);
            JsonNode value = JSON.readTree(text);
            if (value == null) throw new IllegalArgumentException();
            checkStrings(value);
        } catch (Exception failure) {
            throw failure("INVALID_ARGUMENT", field + "不是能够无损保存的完整JSON");
        }
    }

    private static void checkStrings(JsonNode value) throws Exception {
        if (value.isTextual()) requireStoredText(value.textValue());
        else if (value.isObject()) {
            var fields = value.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                requireStoredText(field.getKey());
                checkStrings(field.getValue());
            }
        } else if (value.isArray()) {
            for (JsonNode item : value) checkStrings(item);
        }
    }

    private static void requireStoredText(String text) throws Exception {
        if (text == null || text.indexOf('\0') >= 0) throw new IllegalArgumentException();
        StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text));
    }

    private static RacingServiceException failure(String code, String message) {
        return RacingServiceException.failure(code, message);
    }

    /** 需要保存时，该内容必须经过唯一键写入和提交确认，不能直接作为成功回复。 */
    public record Decision(String valueJson, ValueSource source, boolean requiresAssignment) {
        @Override
        public String toString() {
            return "Decision[sourceKind=" + source.getKind() + ", requiresAssignment=" + requiresAssignment + "]";
        }
    }
}
