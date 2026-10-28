package world.willfrog.racing.server.configuration;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.google.protobuf.Value;
import com.google.protobuf.util.JsonFormat;
import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;
import world.willfrog.racing.idl.ConfigurationCandidate;
import world.willfrog.racing.idl.ConfigurationJsonType;
import world.willfrog.racing.idl.ExperimentConfiguration;
import world.willfrog.racing.idl.ExperimentRunMode;
import world.willfrog.racing.idl.ExperimentScope;
import world.willfrog.racing.idl.ExperimentValueRules;
import world.willfrog.racing.idl.ExperimentValueType;
import world.willfrog.racing.idl.FieldViolation;
import world.willfrog.racing.idl.OfflineReadinessEvidence;
import world.willfrog.racing.idl.PublishedExperimentConfiguration;
import world.willfrog.racing.idl.PromptScope;
import world.willfrog.racing.idl.PromptTargetRef;

/** 通用值只解释有限类型和直接字段，具体业务用途由调用方解释。 */
@Component
public final class ExperimentValueValidator {
    private static final JsonFactory STRICT_JSON = new JsonFactory();

    public void validateDraft(ExperimentConfiguration configuration) {
        validateConfiguration(configuration, false);
    }

    public long validatePublished(ExperimentConfiguration configuration) {
        validateConfiguration(configuration, true);
        return Math.addExact(configuration.getCandidateA().getWeight(), configuration.getCandidateB().getWeight());
    }

    private void validateConfiguration(ExperimentConfiguration c, boolean complete) {
        List<FieldViolation> errors = new ArrayList<>();
        if (complete) {
            required(errors, "name", c.getName());
            required(errors, "purpose", c.getPurpose());
            required(errors, "hypothesis", c.getHypothesis());
            required(errors, "responsible", c.getResponsible());
        }
        if (c.getRunMode() == ExperimentRunMode.UNRECOGNIZED
                || (complete && c.getRunMode() == ExperimentRunMode.EXPERIMENT_RUN_MODE_UNSPECIFIED)) {
            add(errors, "run_mode", "INVALID_RUN_MODE", "需要明确在线或离线方式");
        }
        if (c.hasRules()) {
            validateRules(c.getRules(), errors);
        } else if (complete) {
            add(errors, "rules", "REQUIRED", "发布需要声明合法取值规则");
        }
        validateCandidate(c.getCandidateA(), "candidate_a", c, complete, errors);
        validateCandidate(c.getCandidateB(), "candidate_b", c, complete, errors);
        if (!c.getCandidateA().getCandidateId().isBlank()
                && c.getCandidateA().getCandidateId().equals(c.getCandidateB().getCandidateId())) {
            add(errors, "candidate_b.candidate_id", "DUPLICATE", "两个候选需要不同标识");
        }
        try {
            long total = Math.addExact(c.getCandidateA().getWeight(), c.getCandidateB().getWeight());
            if (complete && total <= 0) add(errors, "weights", "INVALID_WEIGHT", "两个权重合计必须大于零");
        } catch (ArithmeticException e) {
            add(errors, "weights", "INVALID_WEIGHT", "权重合计超出整数范围");
        }
        if (complete && c.getRunMode() == ExperimentRunMode.OFFLINE) {
            required(errors, "dataset_id", c.getDatasetId());
            required(errors, "dataset_version", c.getDatasetVersion());
        }
        validateReferences(c.getPromptReferencesList(), errors, "prompt_references");
        reject(errors);
    }

    private void validateCandidate(ConfigurationCandidate candidate, String path, ExperimentConfiguration c,
            boolean complete, List<FieldViolation> errors) {
        if (complete) {
            required(errors, path + ".candidate_id", candidate.getCandidateId());
            required(errors, path + ".meaning", candidate.getMeaning());
            if (!candidate.hasValueJson()) add(errors, path + ".value_json", "REQUIRED", "需要提供候选值");
        }
        if (candidate.getWeight() < 0) add(errors, path + ".weight", "INVALID_WEIGHT", "权重不能为负数");
        if (candidate.hasValueJson()) {
            try {
                if (c.hasRules()) validateValue(candidate.getValueJson(), c.getRules(), path + ".value_json");
                else validateSupportedValue(candidate.getValueJson(), path + ".value_json");
            } catch (ExperimentConfigurationException e) {
                errors.addAll(e.error().getFieldViolationsList());
            }
        }
    }

    private void validateRules(ExperimentValueRules rules, List<FieldViolation> errors) {
        if (rules.getType() == ExperimentValueType.EXPERIMENT_VALUE_TYPE_UNSPECIFIED
                || rules.getType() == ExperimentValueType.UNRECOGNIZED) {
            add(errors, "rules.type", "INVALID_TYPE", "支持数字、文字或JSON对象");
        }
        if (rules.hasMinimum() && !Double.isFinite(rules.getMinimum()))
            add(errors, "rules.minimum", "INVALID_RANGE", "最小值必须是有限数字");
        if (rules.hasMaximum() && !Double.isFinite(rules.getMaximum()))
            add(errors, "rules.maximum", "INVALID_RANGE", "最大值必须是有限数字");
        if (rules.hasMinimum() && rules.hasMaximum() && rules.getMinimum() > rules.getMaximum())
            add(errors, "rules.maximum", "INVALID_RANGE", "最大值不能小于最小值");
        if (rules.getType() != ExperimentValueType.NUMBER && (rules.hasMinimum() || rules.hasMaximum()))
            add(errors, "rules", "INAPPLICABLE_RULE", "数值范围只适用于数字类型");
        if (rules.getType() != ExperimentValueType.JSON_OBJECT && rules.getRequiredFieldsCount() > 0)
            add(errors, "rules.required_fields", "INAPPLICABLE_RULE", "必需字段只适用于JSON对象");
        Set<String> names = new HashSet<>();
        for (var field : rules.getRequiredFieldsList()) {
            if (field.getName().isBlank() || !names.add(field.getName()))
                add(errors, "rules.required_fields", "INVALID_FIELD", "字段名需要非空并且不能重复");
            if (field.getType() == ConfigurationJsonType.CONFIGURATION_JSON_TYPE_UNSPECIFIED
                    || field.getType() == ConfigurationJsonType.UNRECOGNIZED)
                add(errors, "rules.required_fields", "INVALID_TYPE", "字段需要明确受支持的基础类型");
        }
        for (int i = 0; i < rules.getAllowedValuesJsonCount(); i++) {
            try {
                validateValue(rules.getAllowedValuesJson(i), rules.toBuilder().clearAllowedValuesJson().build(),
                        "rules.allowed_values_json[" + i + "]");
            } catch (ExperimentConfigurationException e) {
                errors.addAll(e.error().getFieldViolationsList());
            }
        }
    }

    public Value validateSupportedValue(String json, String path) {
        Value value = parse(json, path);
        if (value.getKindCase() != Value.KindCase.NUMBER_VALUE
                && value.getKindCase() != Value.KindCase.STRING_VALUE
                && value.getKindCase() != Value.KindCase.STRUCT_VALUE) {
            throw invalidValue(path, "仅支持数字、文字和JSON对象");
        }
        if (value.getKindCase() == Value.KindCase.NUMBER_VALUE && !Double.isFinite(value.getNumberValue()))
            throw invalidValue(path, "数字必须是有限值");
        return value;
    }

    public Value validateValue(String json, ExperimentValueRules rules, String path) {
        Value value = validateSupportedValue(json, path);
        boolean correctType = switch (rules.getType()) {
            case NUMBER -> value.getKindCase() == Value.KindCase.NUMBER_VALUE;
            case STRING -> value.getKindCase() == Value.KindCase.STRING_VALUE;
            case JSON_OBJECT -> value.getKindCase() == Value.KindCase.STRUCT_VALUE;
            default -> false;
        };
        if (!correctType) throw invalidValue(path, "取值与声明类型不符");
        if (rules.getType() == ExperimentValueType.NUMBER
                && ((rules.hasMinimum() && value.getNumberValue() < rules.getMinimum())
                || (rules.hasMaximum() && value.getNumberValue() > rules.getMaximum())) )
            throw invalidValue(path, "数字超出包含边界的合法范围");
        if (rules.getAllowedValuesJsonCount() > 0
                && rules.getAllowedValuesJsonList().stream().noneMatch(j -> parse(j, path).equals(value)))
            throw invalidValue(path, "取值不在允许清单中");
        if (rules.getType() == ExperimentValueType.JSON_OBJECT) {
            for (var field : rules.getRequiredFieldsList()) {
                Value item = value.getStructValue().getFieldsMap().get(field.getName());
                if (item == null || !matches(item, field.getType()))
                    throw invalidValue(path + "." + field.getName(), "缺少必需字段或字段基础类型不符");
            }
        }
        return value;
    }

    private boolean matches(Value value, ConfigurationJsonType type) {
        return switch (type) {
            case JSON_NUMBER -> value.getKindCase() == Value.KindCase.NUMBER_VALUE && Double.isFinite(value.getNumberValue());
            case JSON_STRING -> value.getKindCase() == Value.KindCase.STRING_VALUE;
            case JSON_BOOLEAN -> value.getKindCase() == Value.KindCase.BOOL_VALUE;
            case JSON_MAP -> value.getKindCase() == Value.KindCase.STRUCT_VALUE;
            case JSON_ARRAY -> value.getKindCase() == Value.KindCase.LIST_VALUE;
            case JSON_NULL -> value.getKindCase() == Value.KindCase.NULL_VALUE;
            default -> false;
        };
    }

    public OfflineReadinessEvidence validateActivation(ExperimentScope scope,
            PublishedExperimentConfiguration published, OfflineReadinessEvidence evidence,
            String principal, boolean maySubmitReadiness, Instant now) {
        List<FieldViolation> errors = new ArrayList<>();
        required(errors, "scope.environment", scope.getEnvironment());
        required(errors, "scope.scope_instance_id", scope.getScopeInstanceId());
        if (published == null) add(errors, "published", "REQUIRED", "生效前需要已发布配置");
        reject(errors);
        validatePublished(published.getConfiguration());
        if (published.getConfiguration().getRunMode() != ExperimentRunMode.OFFLINE) return null;
        return validateOfflineReadiness(published.getConfiguration(), evidence, principal, maySubmitReadiness, now);
    }

    public OfflineReadinessEvidence validateOfflineReadiness(ExperimentConfiguration configuration,
            OfflineReadinessEvidence evidence, String principal, boolean maySubmitReadiness, Instant now) {
        List<FieldViolation> errors = new ArrayList<>();
        if (!maySubmitReadiness || principal == null || principal.isBlank())
            throw ExperimentConfigurationException.failure("FORBIDDEN", "调用身份无权提交题集就位凭据");
        if (evidence == null) {
            add(errors, "readiness", "REQUIRED", "离线生效需要题集就位凭据");
            reject(errors);
        }
        if (!configuration.getDatasetId().equals(evidence.getDatasetId())
                || !configuration.getDatasetVersion().equals(evidence.getDatasetVersion()))
            add(errors, "readiness.dataset_version", "DATASET_MISMATCH", "凭据必须对应本配置的固定题集版本");
        if (!evidence.getReady()) add(errors, "readiness.ready", "NOT_READY", "题集尚未就位");
        required(errors, "readiness.evidence_location", evidence.getEvidenceLocation());
        try {
            Instant.parse(evidence.getCheckedAt());
        } catch (DateTimeParseException e) {
            add(errors, "readiness.checked_at", "INVALID_TIME", "需要包含时区的ISO校验时间");
        }
        reject(errors);
        return evidence.toBuilder().setAcceptedFromPrincipal(principal).setAcceptedAt(now.toString()).build();
    }

    public void validateFinal(String json, String meaning, String cleanupRelease,
            PublishedExperimentConfiguration current, List<PromptTargetRef> references) {
        List<FieldViolation> errors = new ArrayList<>();
        required(errors, "meaning", meaning);
        required(errors, "cleanup_release", cleanupRelease);
        validateReferences(references, errors, "prompt_references");
        reject(errors);
        if (current == null) throw ExperimentConfigurationException.failure("INVALID_STATE", "结束需要当前已发布配置");
        validateValue(json, current.getConfiguration().getRules(), "value_json");
    }

    private void validateReferences(List<PromptTargetRef> refs, List<FieldViolation> errors, String path) {
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < refs.size(); i++) {
            var ref = refs.get(i);
            PromptScope scope;
            String id;
            long version;
            if (ref.hasPromptVersion()) {
                scope = ref.getPromptVersion().getScope();
                id = ref.getPromptVersion().getPromptId();
                version = ref.getPromptVersion().getVersion();
            } else if (ref.hasPromptSetVersion()) {
                scope = ref.getPromptSetVersion().getScope();
                id = ref.getPromptSetVersion().getPromptSetId();
                version = ref.getPromptSetVersion().getVersion();
            } else {
                add(errors, path + "[" + i + "]", "INVALID_REFERENCE", "引用需要明确正文或集合编号及正整数版本");
                continue;
            }
            if (scope.getEnvironment().isBlank() || scope.getScopeInstanceId().isBlank() || id.isBlank() || version <= 0)
                add(errors, path + "[" + i + "]", "INVALID_REFERENCE", "引用需要明确来源范围、编号和正整数版本");
            if (!keys.add(ref.getTargetCase().name() + ":" + scope.getEnvironment() + ":"
                    + scope.getScopeInstanceId() + ":" + id + ":" + version))
                add(errors, path + "[" + i + "]", "DUPLICATE", "引用清单不能重复");
        }
    }

    private Value parse(String json, String path) {
        try {
            // JsonFormat容许部分非JSON正文；先用现有Jackson检查完整的单个JSON值。
            try (JsonParser parser = STRICT_JSON.createParser(json)) {
                if (parser.nextToken() == null) throw invalidValue(path, "需要明确JSON值");
                parser.skipChildren();
                if (parser.nextToken() != null) throw invalidValue(path, "只能提供一个完整JSON值");
            }
            Value.Builder builder = Value.newBuilder();
            JsonFormat.parser().merge(json, builder);
            if (builder.getKindCase() == Value.KindCase.KIND_NOT_SET) throw invalidValue(path, "需要明确JSON值");
            return builder.build();
        } catch (IOException | IllegalArgumentException e) {
            throw invalidValue(path, "取值不是合法JSON");
        }
    }

    private static ExperimentConfigurationException invalidValue(String path, String reason) {
        return ExperimentConfigurationException.invalid(List.of(FieldViolation.newBuilder()
                .setField(path).setCode("INVALID_VALUE").setMessage(reason).build()));
    }

    private static void required(List<FieldViolation> errors, String path, String value) {
        if (value == null || value.isBlank()) add(errors, path, "REQUIRED", "需要提供该字段");
    }

    private static void add(List<FieldViolation> errors, String path, String code, String reason) {
        errors.add(FieldViolation.newBuilder().setField(path).setCode(code).setMessage(reason).build());
    }

    private static void reject(List<FieldViolation> errors) {
        if (!errors.isEmpty()) throw ExperimentConfigurationException.invalid(errors);
    }
}
