package world.willfrog.racing.server.prompt;

import static world.willfrog.racing.server.prompt.PromptSupport.*;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.Message;
import com.google.protobuf.util.JsonFormat;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import world.willfrog.racing.idl.*;
import world.willfrog.racing.server.security.AuthorizedCall;
import world.willfrog.racing.server.evaluation.ExperimentEvaluationService;

/**
 * 根归属和业务选择键确定唯一选择，默认变化不会重写已经确认的选择。
 */
@Service
@Transactional
public class PromptSelectionService {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    private final PromptRepository repository;

    private final PromptReadService reads;

    private final PromptSetService sets;

    private final PromptReferenceService references;
    private final ExperimentEvaluationService evaluations;

    public PromptSelectionService(PromptRepository repository, PromptReadService reads, PromptSetService sets,
                                  PromptReferenceService references, ExperimentEvaluationService evaluations) {
        this.repository = repository;
        this.reads = reads;
        this.sets = sets;
        this.references = references;
        this.evaluations = evaluations;
    }

    public PromptSelection resolve(AuthorizedCall call, ResolvePromptSelectionRequest request) {
        require(call.context().hasRoot(), "INVALID_ARGUMENT", "选择需要根运行身份");
        text(request.getSelectionKey(), "selection_key");
        var existing = repository.selection(call.context(), null, request.getSelectionKey());
        if (existing != null) {
            validate(call, existing);
            return existing;
        }
        PromptTargetRef selected;
        PromptSelectionOrigin origin;
        if (request.hasPromptDefault()) {
            require(request.getExperimentId().isEmpty(), "INVALID_ARGUMENT", "根实验选择需要集合默认");
            var choice = request.getPromptDefault();
            read(call, choice.getPrompt().getScope());
            id(choice.getPrompt().getPromptId());
            var record = repository.prompt(choice.getPrompt(), true);
            require(record != null && record.hasDefaultVersion(), "PROMPT_DEFAULT_NOT_FOUND", "提示词尚未设置普通默认");
            var ref = PromptVersionRef.newBuilder()
                                      .setScope(choice.getPrompt().getScope())
                                      .setPromptId(choice.getPrompt().getPromptId())
                                      .setVersion(record.getDefaultVersion())
                                      .build();
            reads.getVersion(call, ref, choice.getExpectedPurpose(), true);
            selected = target(ref);
            origin = ordinaryOrigin(ref.getScope(), record.getRevision(), choice.getPrompt().getPromptId());
        } else if (request.hasPromptSetDefault()) {
            var choice = request.getPromptSetDefault();
            read(call, choice.getScope());
            text(choice.getBusinessName(), "business_name");
            var record = repository.defaultSet(choice.getScope(), choice.getBusinessName(), true);
            require(record != null, "PROMPT_DEFAULT_NOT_FOUND", "该业务尚未设置默认集合");
            if (request.getExperimentId().isEmpty()) {
                selected = target(record.getVersion());
                origin = ordinaryOrigin(record.getVersion().getScope(), record.getRevision(), choice.getBusinessName());
            } else {
                text(request.getExperimentId(), "experiment_id");
                var value = evaluations.evaluate(call, EvaluateRequest.newBuilder().setContext(call.context())
                        .setExperimentId(request.getExperimentId())
                        .setOrdinaryDefaultJson(json(target(record.getVersion())))
                        .setOrdinaryDefaultSourceJson(json(record)).build());
                selected = parseEvaluation(value.getValueJson(), PromptTargetRef.newBuilder()).build();
                require(selected.hasPromptSetVersion(), "INVALID_ARGUMENT", "提示词整组实验值必须引用明确集合版本");
                targetKey(selected);
                var actualSource = value.getSource();
                var experiment = PromptExperimentSource.newBuilder().setExperimentId(request.getExperimentId())
                        .setValueSource(actualSource);
                long defaultRevision = 0;
                if (actualSource.getKind() == ValueSourceKind.ORDINARY_DEFAULT) {
                    var original = parseEvaluation(actualSource.getBusinessDefaultSourceJson(), PromptSetDefault.newBuilder()).build();
                    require(original.getScope().equals(choice.getScope())
                                    && original.getBusinessName().equals(choice.getBusinessName())
                                    && original.getVersion().equals(selected.getPromptSetVersion())
                                    && original.getRevision() > 0,
                            "PROMPT_SOURCE_MISMATCH", "实验普通默认与已保存的默认集合来源不一致");
                    defaultRevision = original.getRevision();
                } else {
                    require(actualSource.getKind() == ValueSourceKind.EXPERIMENT
                                    || actualSource.getKind() == ValueSourceKind.ENDED_FIXED,
                            "PROMPT_SOURCE_MISMATCH", "整组实验缺少实际取值来源");
                }
                if (actualSource.getKind() == ValueSourceKind.ENDED_FIXED) {
                    require(value.getAssignmentId().isEmpty(), "PROMPT_SOURCE_MISMATCH", "结束固定集合不能携带分配编号");
                } else {
                    text(value.getAssignmentId(), "assignment_id");
                    experiment.setAssignmentId(value.getAssignmentId());
                }
                origin = PromptSelectionOrigin.newBuilder().setSourceScope(selected.getPromptSetVersion().getScope())
                        .setBusinessName(choice.getBusinessName()).setDefaultRevision(defaultRevision)
                        .setExperiment(experiment).build();
            }
            sets.readVersion(call, selected.getPromptSetVersion());
            require(repository.setVersion(selected.getPromptSetVersion(), true) != null,
                    "PROMPT_SET_VERSION_NOT_FOUND", "指定集合版本不存在");
        } else {
            throw error("INVALID_ARGUMENT", "需要明确普通提示词或集合默认");
        }
        var selection = PromptSelection.newBuilder()
                                       .setSelectionId(uuid())
                                       .setRoot(call.context().getRoot())
                                       .setTarget(call.context().getTarget())
                                       .setSelectionKey(request.getSelectionKey())
                                       .setSelectedTarget(selected)
                                       .setOrigin(origin)
                                       .setConfirmedAt(now())
                                       .build();
        boolean inserted = repository.insertSelection(selection, call.principal());
        var saved = repository.selection(call.context(), null, request.getSelectionKey());
        require(saved != null, "STORAGE_ERROR", "根选择保存未确认");
        validate(call, saved);
        if (inserted) {
            references.replaceForOwner(call, PromptReferenceReplacement.newBuilder().setOwner(PromptReferenceOwner.newBuilder().setScope(own(call)).setOwnerType("PROMPT_ROOT_SELECTION").setOwnerId(saved.getSelectionId()).setRevision(0)).addTargets(saved.getSelectedTarget()).build());
        }
        return saved;
    }

    private static PromptSelectionOrigin ordinaryOrigin(PromptScope scope, long revision, String business) {
        return PromptSelectionOrigin.newBuilder().setSourceScope(scope).setDefaultRevision(revision)
                .setBusinessName(business).setValueSource(ValueSource.newBuilder()
                        .setKind(ValueSourceKind.ORDINARY_DEFAULT)
                        .setConfigurationScopeInstanceId(scope.getScopeInstanceId())).build();
    }

    private static <T extends Message.Builder> T parseEvaluation(String value, T builder) {
        try {
            var parsed = JSON.readTree(value);
            require(parsed != null && parsed.isObject(), "INVALID_ARGUMENT", "集合实验值及来源必须是完整JSON对象");
            JsonFormat.parser().merge(value, builder);
            return builder;
        } catch (PromptBusinessException failure) {
            throw failure;
        } catch (Exception failure) {
            throw error("INVALID_ARGUMENT", "集合实验值或来源无法解释为明确版本");
        }
    }

    @Transactional(readOnly = true)
    public PromptSelection get(AuthorizedCall call, GetPromptSelectionRequest request) {
        PromptSelection value = getStoredForSource(call, request);
        validate(call, value);
        return value;
    }

    @Transactional(readOnly = true)
    PromptSelection getStoredForSource(AuthorizedCall call, GetPromptSelectionRequest request) {
        require(call.context().hasRoot(), "INVALID_ARGUMENT", "选择需要根运行身份");
        String id = null, key = null;
        if (request.hasSelectionId()) {
            id = request.getSelectionId();
            id(id);
        } else if (request.hasSelectionKey()) {
            key = request.getSelectionKey();
            text(key, "selection_key");
        } else {
            throw error("INVALID_ARGUMENT", "需要选择编号或业务选择键");
        }
        var value = repository.selection(call.context(), id, key);
        require(value != null, "PROMPT_SELECTION_NOT_FOUND", "该根运行没有指定选择");
        validateIdentity(call, value);
        return value;
    }

    private void validate(AuthorizedCall call, PromptSelection selection) {
        validateIdentity(call, selection);
        if (selection.getSelectedTarget().hasPromptVersion()) {
            reads.getVersion(call, selection.getSelectedTarget().getPromptVersion(), "");
        } else if (selection.getSelectedTarget().hasPromptSetVersion()) {
            sets.readVersion(call, selection.getSelectedTarget().getPromptSetVersion());
        } else {
            throw error("STORAGE_ERROR", "选择缺少具体版本");
        }
    }

    private void validateIdentity(AuthorizedCall call, PromptSelection selection) {
        require(selection.getRoot().equals(call.context().getRoot()), "FORBIDDEN", "选择不属于当前根运行");
        require(selection.getTarget()
                         .getEnvironment()
                         .equals(call.actualTarget().getEnvironment()) && selection.getTarget()
                                                                                   .getTargetScopeInstanceId()
                                                                                   .equals(call.actualTarget().getScopeInstanceId()), "SCOPE_MISMATCH", "选择不属于当前目标范围");
    }
}
