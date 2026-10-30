package world.willfrog.racing.server.prompt;

import static world.willfrog.racing.server.prompt.PromptSupport.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import world.willfrog.racing.idl.*;
import world.willfrog.racing.server.security.AuthorizedCall;

/**
 * 引用维护与版本删除锁定相同目标，业务记录与引用同时提交。
 */
@Service
public class DefaultPromptReferenceService implements PromptReferenceService {

    private final PromptRepository repository;

    private final PromptReadService reads;

    private final PromptSetVersionResolver sets;

    public DefaultPromptReferenceService(PromptRepository repository, PromptReadService reads, PromptSetVersionResolver sets) {
        this.repository = repository;
        this.reads = reads;
        this.sets = sets;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void replaceForOwner(AuthorizedCall call, PromptReferenceReplacement replacement) {
        var owner = replacement.getOwner();
        write(call, owner.getScope());
        text(owner.getOwnerType(), "owner_type");
        text(owner.getOwnerId(), "owner_id");
        require(owner.getRevision() >= 0, "INVALID_ARGUMENT", "引用修订不能小于0");
        var targets = new TreeMap<String, PromptTargetRef>();
        for (var target : replacement.getTargetsList()) {
            targets.put(targetKey(target), target);
        }
        String ownerService = ownerService(owner, call);
        var old = repository.ownerReferences(owner, ownerService);
        // 调用业务已锁定自身记录并核对修订；空清单的修订也由该记录保留。
        for (var reference : old) {
            require(reference.getOwner()
                             .getRevision() <= owner.getRevision(), "REVISION_CONFLICT", "不能用旧修订替换引用");
        }
        if (!old.isEmpty() && old.get(0).getOwner().getRevision() == owner.getRevision()) {
            var oldKeys = new TreeSet<String>();
            for (var reference : old) {
                oldKeys.add(targetKey(reference.getTarget()));
            }
            require(oldKeys.equals(targets.keySet()), "REVISION_CONFLICT", "同一引用修订不能使用不同目标清单");
        }
        for (var target : targets.values()) {
            lockReadable(call, target);
        }
        if (!old.isEmpty() && old.get(0).getOwner().getRevision() == owner.getRevision()) {
            return;
        }
        repository.deleteOwnerReferences(owner, ownerService);
        for (var target : targets.values()) {
            repository.insertReference(PromptReference.newBuilder().setReferenceId(uuid()).setOwner(owner).setTarget(target).setRegisteredAt(now()).build(), ownerService);
        }
    }

    private String ownerService(PromptReferenceOwner owner, AuthorizedCall call) {
        // 内置业务归属稳定，修改默认的管理凭据变化不应留下旧默认引用。
        return switch (owner.getOwnerType()) {
            case "PROMPT_DEFAULT", "PROMPT_SET_DEFAULT", "PROMPT_SET_VERSION", "PROMPT_ROOT_SELECTION" -> "racing.prompt";
            case "EXPERIMENT_DRAFT", "EXPERIMENT_CONFIG", "EXPERIMENT_FINAL" -> "racing.experiment";
            default -> call.principal();
        };
    }

    private void lockReadable(AuthorizedCall call, PromptTargetRef target) {
        if (target.hasPromptVersion()) {
            reads.getVersion(call, target.getPromptVersion(), "", true);
            return;
        }
        var ref = target.getPromptSetVersion();
        read(call, ref.getScope());
        var value = repository.setVersion(ref, true);
        require(value != null, "PROMPT_SET_VERSION_NOT_FOUND", "指定集合版本不存在");
        sets.resolveDefinition(call, value);
    }

    @Transactional(readOnly = true)
    public PromptReferenceList list(AuthorizedCall call, ListPromptReferencesRequest request) {
        targetKey(request.getTarget());
        write(call, scope(request.getTarget()));
        var page = page(request.getPage());
        return PromptReferenceList.newBuilder()
                                  .addAllReferences(repository.references(request.getTarget(), page.offset(), page.size()))
                                  .setPage(page.info(repository.referenceCount(request.getTarget())))
                                  .build();
    }

    @Transactional
    public DeletePromptTargetReply deleteUnused(AuthorizedCall call, PromptTargetRef target) {
        targetKey(target);
        write(call, scope(target));
        if (target.hasPromptVersion()) {
            require(repository.version(target.getPromptVersion(), true) != null, "PROMPT_VERSION_NOT_FOUND", "指定提示词版本不存在");
        } else {
            require(repository.setVersion(target.getPromptSetVersion(), true) != null, "PROMPT_SET_VERSION_NOT_FOUND", "指定集合版本不存在");
        }
        long count = repository.referenceCount(target);
        if (count > 0) {
            return DeletePromptTargetReply.newBuilder()
                                          .setActualTarget(call.actualTarget())
                                          .setError(RacingError.newBuilder().setCode("PROMPT_VERSION_REFERENCED").setMessage("该版本仍被业务记录引用，不能删除"))
                                          .setBlockingReferences(PromptReferenceList.newBuilder().addAllReferences(repository.references(target, 0, 20)).setPage(PromptPageInfo.newBuilder().setPageNumber(1).setPageSize(20).setTotalCount(count)))
                                          .build();
        }
        if (target.hasPromptVersion()) {
            repository.deleteVersion(target.getPromptVersion());
        } else {
            repository.deleteSetMemberReferences(target.getPromptSetVersion());
            repository.deleteSetVersion(target.getPromptSetVersion());
        }
        return DeletePromptTargetReply.newBuilder()
                                      .setActualTarget(call.actualTarget())
                                      .setDeleted(PromptDeletionResult.newBuilder().setDeletedTarget(target))
                                      .build();
    }
}
