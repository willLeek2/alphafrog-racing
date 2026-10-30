package world.willfrog.racing.server.prompt;

import static world.willfrog.racing.server.prompt.PromptSupport.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import world.willfrog.racing.idl.*;
import world.willfrog.racing.server.security.AuthorizedCall;

/**
 * 集合成员只引用具体正文版本，默认集合与成员引用在同一事务维护。
 */
@Service
@Transactional
public class PromptSetService {

    private final PromptRepository repository;

    private final PromptSetVersionResolver resolver;

    private final PromptReferenceService references;

    public PromptSetService(PromptRepository repository, PromptSetVersionResolver resolver, PromptReferenceService references) {
        this.repository = repository;
        this.resolver = resolver;
        this.references = references;
    }

    public PromptSetRecord create(AuthorizedCall call, CreatePromptSetRequest request) {
        write(call, request.getScope());
        text(request.getName(), "name");
        var value = PromptSetRecord.newBuilder()
                                   .setLocator(PromptSetLocator.newBuilder().setScope(request.getScope()).setPromptSetId(uuid()))
                                   .setName(request.getName())
                                   .setRevision(1)
                                   .setCreation(creation(call))
                                   .setLastModification(modification(call, "CREATE"))
                                   .build();
        require(repository.insertSet(value), "PROMPT_NAME_EXISTS", "当前范围已有同名集合");
        return repository.promptSet(value.getLocator(), false);
    }

    private PromptSetRecord locked(AuthorizedCall call, PromptSetLocator key) {
        write(call, key.getScope());
        id(key.getPromptSetId());
        var value = repository.promptSet(key, true);
        require(value != null, "PROMPT_SET_NOT_FOUND", "集合不存在");
        return value;
    }

    public PromptSetRecord updateMetadata(AuthorizedCall call, UpdatePromptSetMetadataRequest request) {
        var value = locked(call, request.getPromptSet());
        revision(value.getRevision(), request.getExpectedRevision());
        text(request.getName(), "name");
        repository.updateSet(value.toBuilder().setName(request.getName()).setRevision(value.getRevision() + 1).setLastModification(modification(call, "UPDATE_METADATA")).build(), value.getRevision());
        return repository.promptSet(request.getPromptSet(), false);
    }

    public PromptSetDetails createVersion(AuthorizedCall call, CreatePromptSetVersionRequest request) {
        var record = locked(call, request.getPromptSet());
        revision(record.getRevision(), request.getExpectedRevision());
        var definition = PromptSetVersion.newBuilder()
                                         .setReference(PromptSetVersionRef.newBuilder().setScope(request.getPromptSet().getScope()).setPromptSetId(request.getPromptSet().getPromptSetId()).setVersion(record.getRevision()))
                                         .addAllMembers(request.getMembersList())
                                         .addAllRemovedFragmentNames(request.getRemovedFragmentNamesList())
                                         .setCreation(creation(call));
        if (request.hasBaseVersion()) {
            definition.setBaseVersion(request.getBaseVersion());
        }
        // 校验临时展开结果；持久化对象始终是请求提交的原始定义。
        var version = definition.build();
        resolver.resolveDefinition(call, version);
        var refs = PromptReferenceReplacement.newBuilder()
                                             .setOwner(PromptReferenceOwner.newBuilder().setScope(request.getPromptSet().getScope()).setOwnerType("PROMPT_SET_VERSION").setOwnerId(request.getPromptSet()
                                                                                                                                                                                          .getPromptSetId() + ":" + record.getRevision()).setRevision(0));
        for (var member : request.getMembersList()) {
            refs.addTargets(target(member.getVersion()));
        }
        if (request.hasBaseVersion()) {
            refs.addTargets(target(request.getBaseVersion()));
        }
        references.replaceForOwner(call, refs.build());
        repository.insertSetVersion(version);
        repository.updateSet(record.toBuilder().setRevision(record.getRevision() + 1).setLastModification(modification(call, "CREATE_VERSION")).build(), record.getRevision());
        return PromptSetDetails.newBuilder()
                               .setRecord(repository.promptSet(request.getPromptSet(), false))
                               .setVersion(repository.setVersion(version.getReference(), false))
                               .build();
    }

    @Transactional(readOnly = true)
    public PromptSetVersion readVersion(AuthorizedCall call, PromptSetVersionRef ref) {
        return readVersion(call, ref, new java.util.HashMap<>());
    }

    PromptSetVersion readVersion(AuthorizedCall call, PromptSetVersionRef ref, java.util.Map<PromptVersionRef, PromptVersion> versions) {
        return resolver.resolve(call, ref, versions);
    }

    private PromptSetVersion storedDefinition(AuthorizedCall call, PromptSetVersionRef ref) {
        targetKey(target(ref));
        read(call, ref.getScope());
        PromptSetVersion definition = repository.setVersion(ref, false);
        require(definition != null, "PROMPT_SET_VERSION_NOT_FOUND", "指定集合版本不存在");
        resolver.resolveDefinition(call, definition);
        return definition;
    }

    @Transactional(readOnly = true)
    public PromptSetDetails get(AuthorizedCall call, GetPromptSetRequest request) {
        read(call, request.getPromptSet().getScope());
        id(request.getPromptSet().getPromptSetId());
        var record = repository.promptSet(request.getPromptSet(), false);
        require(record != null, "PROMPT_SET_NOT_FOUND", "集合不存在");
        var b = PromptSetDetails.newBuilder()
                                .setRecord(record);
        if (request.hasVersion()) {
            b.setVersion(storedDefinition(call, PromptSetVersionRef.newBuilder().setScope(request.getPromptSet().getScope()).setPromptSetId(request.getPromptSet().getPromptSetId()).setVersion(request.getVersion()).build()));
        }
        return b.build();
    }

    @Transactional(readOnly = true)
    public PromptSetRecordList list(AuthorizedCall call, ListPromptSetsRequest request) {
        read(call, request.getScope());
        var page = page(request.getPage());
        return PromptSetRecordList.newBuilder()
                                  .addAllPromptSets(repository.sets(request.getScope(), request.getNameFilter(), page.offset(), page.size()))
                                  .setPage(page.info(repository.setCount(request.getScope(), request.getNameFilter())))
                                  .build();
    }

    @Transactional(readOnly = true)
    public PromptSetVersionList listVersions(AuthorizedCall call, ListPromptSetVersionsRequest request) {
        read(call, request.getPromptSet().getScope());
        id(request.getPromptSet().getPromptSetId());
        var page = page(request.getPage());
        var b = PromptSetVersionList.newBuilder();
        for (var version : repository.setVersions(request.getPromptSet(), page.offset(), page.size())) {
            resolver.resolveDefinition(call, version);
            b.addVersions(version);
        }
        return b.setPage(page.info(repository.setVersionCount(request.getPromptSet())))
                .build();
    }

    public PromptSetDefault setDefault(AuthorizedCall call, SetPromptSetDefaultRequest request) {
        write(call, request.getScope());
        text(request.getBusinessName(), "business_name");
        readVersion(call, request.getVersion());
        var existing = repository.defaultSet(request.getScope(), request.getBusinessName(), true);
        long previous = existing == null ? 0 : existing.getRevision();
        revision(previous, request.getExpectedRevision());
        require(repository.setVersion(request.getVersion(), true) != null,
                "PROMPT_SET_VERSION_NOT_FOUND", "指定集合版本不存在");
        var value = PromptSetDefault.newBuilder()
                                    .setScope(request.getScope())
                                    .setBusinessName(request.getBusinessName())
                                    .setVersion(request.getVersion())
                                    .setRevision(previous + 1)
                                    .setLastModification(modification(call, "SET_DEFAULT"))
                                    .build();
        if (existing == null) {
            require(repository.insertDefaultSet(value), "REVISION_CONFLICT", "默认集合已由其他请求创建，请刷新");
        }
        references.replaceForOwner(call, PromptReferenceReplacement.newBuilder().setOwner(PromptReferenceOwner.newBuilder().setScope(request.getScope()).setOwnerType("PROMPT_SET_DEFAULT").setOwnerId(request.getBusinessName()).setRevision(value.getRevision())).addTargets(target(request.getVersion())).build());
        if (existing != null) {
            repository.updateDefaultSet(value, previous);
        }
        return repository.defaultSet(request.getScope(), request.getBusinessName(), false);
    }

    @Transactional(readOnly = true)
    public PromptSetDefault getDefault(AuthorizedCall call, GetPromptSetDefaultRequest request) {
        read(call, request.getScope());
        text(request.getBusinessName(), "business_name");
        var value = repository.defaultSet(request.getScope(), request.getBusinessName(), false);
        require(value != null, "PROMPT_DEFAULT_NOT_FOUND", "该业务尚未设置默认集合");
        readVersion(call, value.getVersion());
        return value;
    }
}
