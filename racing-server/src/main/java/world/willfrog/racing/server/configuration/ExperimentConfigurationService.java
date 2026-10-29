package world.willfrog.racing.server.configuration;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import world.willfrog.racing.idl.*;
import world.willfrog.racing.server.prompt.PromptReferenceService;
import world.willfrog.racing.server.security.AuthorizedCall;

/** 管理动作由公共入口鉴权；配置、引用和操作记录使用同一数据库事务。 */
@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class ExperimentConfigurationService {
    private final ExperimentConfigurationRepository repository;
    private final ExperimentValueValidator validator;
    private final PromptReferenceService references;

    public ExperimentConfigurationService(ExperimentConfigurationRepository repository,
            ExperimentValueValidator validator, PromptReferenceService references) {
        this.repository = repository;
        this.validator = validator;
        this.references = references;
    }

    public ExperimentRecord create(AuthorizedCall call, CreateExperimentRequest request) {
        validator.validateDraft(request.getDraft());
        Instant now = Instant.now();
        ExperimentRecord record = ExperimentRecord.newBuilder()
                .setExperimentId(UUID.randomUUID().toString()).setScope(scope(call))
                .setStatus(ExperimentStatus.DRAFT).setRevision(1).setDraftRevision(1)
                .setDraft(request.getDraft()).setCreatedAt(now.toString()).setUpdatedAt(now.toString()).build();
        repository.insert(record);
        replaceReferences(call, record, "EXPERIMENT_DRAFT", record.getExperimentId(), 1,
                record.getDraft().getPromptReferencesList());
        appendOperation(call, "CREATE", 0, record, null, now);
        return record;
    }

    public ExperimentRecord saveDraft(AuthorizedCall call, SaveExperimentDraftRequest request) {
        ExperimentRecord before = lock(call, request.getExperimentId(), request.getExpectedRevision());
        requireEditable(before);
        validator.validateDraft(request.getDraft());
        Instant now = Instant.now();
        ExperimentRecord after = changed(before, now).setDraft(request.getDraft())
                .setDraftRevision(before.getDraftRevision() + 1).build();
        repository.updateWithRevision(after, before.getRevision());
        replaceReferences(call, after, "EXPERIMENT_DRAFT", after.getExperimentId(), after.getDraftRevision(),
                after.getDraft().getPromptReferencesList());
        appendOperation(call, "SAVE_DRAFT", before.getRevision(), after, null, now);
        return after;
    }

    public ExperimentRecord publish(AuthorizedCall call, ExperimentMutationRequest request) {
        ExperimentRecord before = lock(call, request.getExperimentId(), request.getExpectedRevision());
        requireEditable(before);
        long total = validator.validatePublished(before.getDraft());
        Instant now = Instant.now();
        PublishedExperimentConfiguration published = PublishedExperimentConfiguration.newBuilder()
                .setExperimentId(before.getExperimentId())
                .setVersion(before.hasPublished() ? before.getPublished().getVersion() + 1 : 1)
                .setConfiguration(before.getDraft()).setTotalWeight(total)
                .setPublishedBy(call.principal()).setPublishedAt(now.toString()).build();
        OfflineReadinessEvidence readiness = before.getStatus() == ExperimentStatus.RUNNING
                ? activation(call, before.getScope(), published, request, now) : null;
        repository.insertVersion(before.getScope(), published);
        ExperimentRecord after = changed(before, now).setPublished(published).build();
        repository.updateWithRevision(after, before.getRevision());
        replaceReferences(call, after, "EXPERIMENT_CONFIG", after.getExperimentId() + ":" + published.getVersion(),
                published.getVersion(), published.getConfiguration().getPromptReferencesList());
        appendOperation(call, "PUBLISH", before.getRevision(), after, readiness, now);
        return after;
    }

    public ExperimentRecord start(AuthorizedCall call, ExperimentMutationRequest request) {
        return transition(call, request, "START", ExperimentStatus.DRAFT, ExperimentStatus.RUNNING);
    }

    public ExperimentRecord pause(AuthorizedCall call, ExperimentMutationRequest request) {
        return transition(call, request, "PAUSE", ExperimentStatus.RUNNING, ExperimentStatus.PAUSED);
    }

    public ExperimentRecord resume(AuthorizedCall call, ExperimentMutationRequest request) {
        return transition(call, request, "RESUME", ExperimentStatus.PAUSED, ExperimentStatus.RUNNING);
    }

    private ExperimentRecord transition(AuthorizedCall call, ExperimentMutationRequest request, String action,
            ExperimentStatus expected, ExperimentStatus target) {
        ExperimentRecord before = lock(call, request.getExperimentId(), request.getExpectedRevision());
        requireStatus(before, expected);
        Instant now = Instant.now();
        OfflineReadinessEvidence readiness = target == ExperimentStatus.RUNNING
                ? activation(call, before.getScope(), before.hasPublished() ? before.getPublished() : null,
                        request, now) : null;
        ExperimentRecord after = changed(before, now).setStatus(target).build();
        repository.updateWithRevision(after, before.getRevision());
        appendOperation(call, action, before.getRevision(), after, readiness, now);
        return after;
    }

    public ExperimentRecord end(AuthorizedCall call, EndExperimentRequest request) {
        ExperimentRecord before = lock(call, request.getExperimentId(), request.getExpectedRevision());
        requireStatus(before, ExperimentStatus.RUNNING, ExperimentStatus.PAUSED);
        validator.validateFinal(request.getValueJson(), request.getMeaning(), request.getCleanupRelease(),
                before.hasPublished() ? before.getPublished() : null, request.getPromptReferencesList());
        Instant now = Instant.now();
        long revision = before.getRevision() + 1;
        ExperimentFinalRecord ending = ExperimentFinalRecord.newBuilder()
                .setValueJson(request.getValueJson()).setMeaning(request.getMeaning())
                .setCleanupRelease(request.getCleanupRelease())
                .setConfigurationVersion(before.getPublished().getVersion()).setEndRevision(revision)
                .addAllPromptReferences(request.getPromptReferencesList()).build();
        ExperimentRecord after = changed(before, now).setStatus(ExperimentStatus.ENDED).setFinalRecord(ending).build();
        repository.updateWithRevision(after, before.getRevision());
        replaceReferences(call, after, "EXPERIMENT_FINAL", after.getExperimentId() + ":" + revision, revision,
                request.getPromptReferencesList());
        appendOperation(call, "END", before.getRevision(), after, null, now);
        return after;
    }

    @Transactional(readOnly = true)
    public ExperimentDeletePreview previewDelete(AuthorizedCall call, GetExperimentRequest request) {
        return deletionPreview(get(call, request));
    }

    public ExperimentRecord delete(AuthorizedCall call, ExperimentMutationRequest request) {
        ExperimentRecord before = lock(call, request.getExperimentId(), request.getExpectedRevision());
        if (!deletionPreview(before).getAllowed()) {
            throw ExperimentConfigurationException.failure("INVALID_STATE", "只有草稿或已结束实验可以删除");
        }
        Instant now = Instant.now();
        ExperimentRecord after = changed(before, now).setDeletedAt(now.toString()).build();
        repository.updateWithRevision(after, before.getRevision());
        // 软删除保留历史配置及其正文引用，不将历史关联当作活动依赖。
        appendOperation(call, "DELETE", before.getRevision(), after, null, now);
        return after;
    }

    @Transactional(readOnly = true)
    public ExperimentRecord get(AuthorizedCall call, GetExperimentRequest request) {
        return requireRecord(repository.find(scope(call), requireId(request.getExperimentId())));
    }

    @Transactional(readOnly = true)
    public ExperimentRecords list(AuthorizedCall call, ListExperimentsRequest request) {
        for (ExperimentStatus status : request.getStatusesList()) {
            if (status == ExperimentStatus.EXPERIMENT_STATUS_UNSPECIFIED || status == ExperimentStatus.UNRECOGNIZED)
                throw ExperimentConfigurationException.failure("INVALID_ARGUMENT", "状态筛选值无效");
        }
        return ExperimentRecords.newBuilder().addAllExperiments(repository.list(scope(call),
                request.getStatusesList(), request.getIncludeDeleted(), offset(request.getOffset()),
                limit(request.getLimit()))).build();
    }

    @Transactional(readOnly = true)
    public ExperimentVersions listVersions(AuthorizedCall call, GetExperimentRequest request) {
        get(call, request);
        return ExperimentVersions.newBuilder().addAllVersions(repository.listVersions(scope(call),
                request.getExperimentId(), offset(request.getOffset()), limit(request.getLimit()))).build();
    }

    @Transactional(readOnly = true)
    public ExperimentOperations listOperations(AuthorizedCall call, GetExperimentRequest request) {
        get(call, request);
        return ExperimentOperations.newBuilder().addAllOperations(repository.listOperations(scope(call),
                request.getExperimentId(), offset(request.getOffset()), limit(request.getLimit()))).build();
    }

    /** 共享行锁保持到取值事务结束；未发布草稿的 published 字段保持缺省。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public ExperimentRecord lockForEvaluation(ExperimentScope scope, String experimentId) {
        ExperimentRecord record = requireRecord(repository.lockForShare(scope, requireId(experimentId)));
        requireNotDeleted(record);
        return record;
    }

    private ExperimentRecord lock(AuthorizedCall call, String experimentId, long revision) {
        ExperimentRecord record = requireRecord(repository.lockForUpdate(scope(call), requireId(experimentId)));
        requireNotDeleted(record);
        if (record.getRevision() != revision) {
            throw ExperimentConfigurationException.failure("REVISION_CONFLICT",
                    "修改序号已过期，当前序号为" + record.getRevision());
        }
        return record;
    }

    private OfflineReadinessEvidence activation(AuthorizedCall call, ExperimentScope scope,
            PublishedExperimentConfiguration published, ExperimentMutationRequest request, Instant now) {
        return validator.validateActivation(scope, published, request.hasReadiness() ? request.getReadiness() : null,
                call.principal(), call.maySubmitOfflineReadiness(), now);
    }

    private void replaceReferences(AuthorizedCall call, ExperimentRecord record, String type, String id,
            long revision, List<PromptTargetRef> targets) {
        PromptReferenceOwner owner = PromptReferenceOwner.newBuilder()
                .setScope(PromptScope.newBuilder().setEnvironment(record.getScope().getEnvironment())
                        .setScopeInstanceId(record.getScope().getScopeInstanceId()))
                .setOwnerType(type).setOwnerId(id).setRevision(revision).build();
        references.replaceForOwner(call, PromptReferenceReplacement.newBuilder()
                .setOwner(owner).addAllTargets(targets).build());
    }

    private void appendOperation(AuthorizedCall call, String action, long beforeRevision, ExperimentRecord after,
            OfflineReadinessEvidence readiness, Instant now) {
        ExperimentOperation.Builder operation = ExperimentOperation.newBuilder()
                .setOperationId(UUID.randomUUID().toString()).setExperimentId(after.getExperimentId())
                .setAction(action).setActor(call.principal()).setBeforeRevision(beforeRevision)
                .setAfterRevision(after.getRevision()).setOperatedAt(now.toString());
        if (after.hasPublished()) operation.setConfigurationVersion(after.getPublished().getVersion());
        if (readiness != null) operation.setReadiness(readiness);
        repository.appendOperation(after.getScope(), operation.build());
    }

    private static ExperimentDeletePreview deletionPreview(ExperimentRecord record) {
        boolean allowed = record.getDeletedAt().isEmpty()
                && (record.getStatus() == ExperimentStatus.DRAFT || record.getStatus() == ExperimentStatus.ENDED);
        ExperimentDeletePreview.Builder preview = ExperimentDeletePreview.newBuilder()
                .setExperimentId(record.getExperimentId()).setStatus(record.getStatus()).setRevision(record.getRevision())
                .setAllowed(allowed).setCheckedCoverage("仅核对平台已登记对象：实验当前状态；未扫描业务代码、部署配置或离线批次");
        if (!allowed) preview.addBlockingObjects(ExperimentDeleteDependency.newBuilder()
                .setObjectType("EXPERIMENT_STATE").setObjectId(record.getExperimentId())
                .setReason(record.getDeletedAt().isEmpty() ? "运行中或暂停的实验不能删除" : "实验已经删除"));
        return preview.build();
    }

    private static ExperimentRecord.Builder changed(ExperimentRecord before, Instant now) {
        return before.toBuilder().setRevision(before.getRevision() + 1).setUpdatedAt(now.toString());
    }

    private static ExperimentScope scope(AuthorizedCall call) {
        return ExperimentScope.newBuilder().setEnvironment(call.actualTarget().getEnvironment())
                .setScopeInstanceId(call.actualTarget().getScopeInstanceId()).build();
    }

    private static ExperimentRecord requireRecord(ExperimentRecord record) {
        if (record == null) throw ExperimentConfigurationException.failure("NOT_FOUND", "实验不存在");
        return record;
    }

    private static void requireNotDeleted(ExperimentRecord record) {
        if (!record.getDeletedAt().isEmpty()) throw ExperimentConfigurationException.failure("NOT_FOUND", "实验已删除");
    }

    private static String requireId(String id) {
        if (id.isBlank()) throw ExperimentConfigurationException.failure("INVALID_ARGUMENT", "需要实验编号");
        return id;
    }

    private static void requireEditable(ExperimentRecord record) {
        requireStatus(record, ExperimentStatus.DRAFT, ExperimentStatus.RUNNING, ExperimentStatus.PAUSED);
    }

    private static void requireStatus(ExperimentRecord record, ExperimentStatus... allowed) {
        if (!List.of(allowed).contains(record.getStatus())) {
            throw ExperimentConfigurationException.failure("INVALID_STATE", "当前实验状态不允许此动作：" + record.getStatus());
        }
    }

    private static int offset(int value) {
        if (value < 0) throw ExperimentConfigurationException.failure("INVALID_ARGUMENT", "分页起点不能为负数");
        return value;
    }

    private static int limit(int value) {
        if (value < 0 || value > 200) throw ExperimentConfigurationException.failure("INVALID_ARGUMENT", "分页大小需要在1至200之间");
        return value == 0 ? 50 : value;
    }
}
