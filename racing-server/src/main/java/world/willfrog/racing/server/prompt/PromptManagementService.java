package world.willfrog.racing.server.prompt;

import static world.willfrog.racing.server.prompt.PromptSupport.*;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import world.willfrog.racing.idl.*;
import world.willfrog.racing.server.security.AuthorizedCall;

/**
 * 管理命名记录和不可变正文，设置普通默认是独立修改。
 */
@Service
@Transactional
public class PromptManagementService {

    private final PromptRepository repository;

    private final PromptReadService reads;

    private final PromptReferenceService references;

    private final PromptContentValidator validator = new PromptContentValidator();

    public PromptManagementService(PromptRepository repository, PromptReadService reads, PromptReferenceService references) {
        this.repository = repository;
        this.reads = reads;
        this.references = references;
    }

    public PromptDetails create(AuthorizedCall call, CreatePromptRequest request) {
        write(call, request.getScope());
        text(request.getName(), "name");
        validator.validate(request.getInitialVersion());
        var record = PromptRecord.newBuilder()
                                 .setLocator(PromptLocator.newBuilder().setScope(request.getScope()).setPromptId(uuid()))
                                 .setName(request.getName())
                                 .setRevision(1)
                                 .setCreation(creation(call))
                                 .setLastModification(modification(call, "CREATE"))
                                 .build();
        if (!repository.insertPrompt(record)) {
            var existing = repository.promptByName(request.getScope(), request.getName());
            var current = repository.prompt(existing.getLocator(), true);
            var prior = findImport(current.getLocator(), request.getInitialVersion());
            require(prior != null, "PROMPT_NAME_EXISTS", "当前范围已有同名提示词");
            return PromptDetails.newBuilder()
                                .setRecord(current)
                                .setVersion(prior)
                                .build();
        }
        var version = buildVersion(call, record.getLocator(), 1, request.getInitialVersion());
        repository.insertVersion(version);
        return PromptDetails.newBuilder()
                            .setRecord(repository.prompt(record.getLocator(), false))
                            .setVersion(repository.version(version.getReference(), false))
                            .build();
    }

    public PromptRecord updateMetadata(AuthorizedCall call, UpdatePromptMetadataRequest request) {
        var record = locked(call, request.getPrompt());
        text(request.getName(), "name");
        revision(record.getRevision(), request.getExpectedRevision());
        var updated = record.toBuilder()
                            .setName(request.getName())
                            .setRevision(record.getRevision() + 1)
                            .setLastModification(modification(call, "UPDATE_METADATA"))
                            .build();
        repository.updatePrompt(updated, record.getRevision());
        return repository.prompt(request.getPrompt(), false);
    }

    public PromptDetails createVersion(AuthorizedCall call, CreatePromptVersionRequest request) {
        var record = locked(call, request.getPrompt());
        validator.validate(request.getContent());
        var existing = findImport(request.getPrompt(), request.getContent());
        if (existing != null) {
            return PromptDetails.newBuilder()
                                .setRecord(record)
                                .setVersion(existing)
                                .build();
        }
        revision(record.getRevision(), request.getExpectedRevision());
        long next = record.getRevision() + 1;
        var version = buildVersion(call, request.getPrompt(), next, request.getContent());
        repository.insertVersion(version);
        repository.updatePrompt(record.toBuilder().setRevision(next).setLastModification(modification(call, "CREATE_VERSION")).build(), record.getRevision());
        return PromptDetails.newBuilder()
                            .setRecord(repository.prompt(request.getPrompt(), false))
                            .setVersion(repository.version(version.getReference(), false))
                            .build();
    }

    public PromptRecord setDefault(AuthorizedCall call, SetPromptDefaultRequest request) {
        var record = locked(call, request.getPrompt());
        revision(record.getRevision(), request.getExpectedRevision());
        var ref = PromptVersionRef.newBuilder()
                                  .setScope(request.getPrompt().getScope())
                                  .setPromptId(request.getPrompt().getPromptId())
                                  .setVersion(request.getVersion())
                                  .build();
        reads.getVersion(call, ref, "");
        long next = record.getRevision() + 1;
        references.replaceForOwner(call, PromptReferenceReplacement.newBuilder().setOwner(PromptReferenceOwner.newBuilder().setScope(request.getPrompt().getScope()).setOwnerType("PROMPT_DEFAULT").setOwnerId(request.getPrompt().getPromptId()).setRevision(next)).addTargets(target(ref)).build());
        repository.updatePrompt(record.toBuilder().setDefaultVersion(request.getVersion()).setRevision(next).setLastModification(modification(call, "SET_DEFAULT")).build(), record.getRevision());
        return repository.prompt(request.getPrompt(), false);
    }

    public PromptVersion updateAuthorization(AuthorizedCall call, UpdatePromptVersionAuthorizationRequest request) {
        targetKey(target(request.getVersion()));
        write(call, request.getVersion().getScope());
        var version = repository.version(request.getVersion(), true);
        require(version != null, "PROMPT_VERSION_NOT_FOUND", "指定提示词版本不存在");
        revision(version.getAuthorization().getRevision(), request.getExpectedAuthorizationRevision());
        for (var scope : request.getReadableScopesList()) {
            scope(scope);
        }
        var updated = version.toBuilder()
                             .setAuthorization(PromptVersionAuthorization.newBuilder().setRevision(version.getAuthorization()
                                                                                                          .getRevision() + 1).addAllReadableScopes(request.getReadableScopesList()))
                             .build();
        repository.updateAuthorization(updated, version.getAuthorization().getRevision());
        return repository.version(request.getVersion(), false);
    }

    private PromptRecord locked(AuthorizedCall call, PromptLocator key) {
        write(call, key.getScope());
        id(key.getPromptId());
        var record = repository.prompt(key, true);
        require(record != null, "PROMPT_NOT_FOUND", "提示词不存在");
        return record;
    }

    private PromptVersion buildVersion(AuthorizedCall call, PromptLocator key, long number, PromptVersionInput input) {
        var checked = validator.validate(input);
        var b = PromptVersion.newBuilder()
                             .setReference(PromptVersionRef.newBuilder().setScope(key.getScope()).setPromptId(key.getPromptId()).setVersion(number))
                             .setBody(input.getBody())
                             .setBodyType(input.getBodyType())
                             .addAllVariables(input.getVariablesList())
                             .setPurpose(input.getPurpose())
                             .setAuthorization(PromptVersionAuthorization.newBuilder().setRevision(1).addAllReadableScopes(input.getReadableScopesList()))
                             .setUtf8Length(checked.utf8Length())
                             .setSha256(checked.sha256())
                             .setCreation(creation(call));
        if (input.hasImportOrigin()) {
            b.setImportOrigin(input.getImportOrigin());
        }
        return b.build();
    }

    private PromptVersion findImport(PromptLocator key, PromptVersionInput input) {
        if (!input.hasImportOrigin()) {
            return null;
        }
        // 同一来源修订重发只复用原正文；内容改变必须使用新来源修订。
        for (int offset = 0; ; offset += 100) {
            List<PromptVersion> versions = repository.versions(key, offset, 100);
            for (var version : versions) {
                if (version.hasImportOrigin() && version.getImportOrigin().equals(input.getImportOrigin())) {
                    require(version.getBody()
                                   .equals(input.getBody()) && version.getBodyType() == input.getBodyType() && version.getPurpose()
                                                                                                                      .equals(input.getPurpose()) && version.getVariablesList()
                                                                                                                                                            .equals(input.getVariablesList()), "REVISION_CONFLICT", "同一导入来源不能对应不同正文");
                    return version;
                }
            }
            if (versions.size() < 100) {
                return null;
            }
        }
    }
}
