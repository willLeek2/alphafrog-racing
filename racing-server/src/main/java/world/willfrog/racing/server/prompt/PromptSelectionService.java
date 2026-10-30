package world.willfrog.racing.server.prompt;

import static world.willfrog.racing.server.prompt.PromptSupport.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import world.willfrog.racing.idl.*;
import world.willfrog.racing.server.security.AuthorizedCall;

/**
 * 根归属和业务选择键确定唯一选择，默认变化不会重写已经确认的选择。
 */
@Service
@Transactional
public class PromptSelectionService {

    private final PromptRepository repository;

    private final PromptReadService reads;

    private final PromptSetService sets;

    private final PromptReferenceService references;

    public PromptSelectionService(PromptRepository repository, PromptReadService reads, PromptSetService sets, PromptReferenceService references) {
        this.repository = repository;
        this.reads = reads;
        this.sets = sets;
        this.references = references;
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
        PromptScope source;
        long revision;
        String business;
        if (request.hasPromptDefault()) {
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
            source = ref.getScope();
            revision = record.getRevision();
            business = choice.getPrompt()
                             .getPromptId();
        } else if (request.hasPromptSetDefault()) {
            var choice = request.getPromptSetDefault();
            read(call, choice.getScope());
            text(choice.getBusinessName(), "business_name");
            var record = repository.defaultSet(choice.getScope(), choice.getBusinessName(), true);
            require(record != null, "PROMPT_DEFAULT_NOT_FOUND", "该业务尚未设置默认集合");
            sets.readVersion(call, record.getVersion());
            require(repository.setVersion(record.getVersion(), true) != null,
                    "PROMPT_SET_VERSION_NOT_FOUND", "指定集合版本不存在");
            selected = target(record.getVersion());
            source = record.getVersion()
                           .getScope();
            revision = record.getRevision();
            business = choice.getBusinessName();
        } else {
            throw error("INVALID_ARGUMENT", "需要明确普通提示词或集合默认");
        }
        var valueSource = ValueSource.newBuilder()
                                     .setKind(ValueSourceKind.ORDINARY_DEFAULT)
                                     .setConfigurationScopeInstanceId(source.getScopeInstanceId())
                                     .build();
        var selection = PromptSelection.newBuilder()
                                       .setSelectionId(uuid())
                                       .setRoot(call.context().getRoot())
                                       .setTarget(call.context().getTarget())
                                       .setSelectionKey(request.getSelectionKey())
                                       .setSelectedTarget(selected)
                                       .setOrigin(PromptSelectionOrigin.newBuilder().setSourceScope(source).setDefaultRevision(revision).setBusinessName(business).setValueSource(valueSource))
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
