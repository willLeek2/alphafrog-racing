package world.willfrog.racing.server.prompt;

import static world.willfrog.racing.server.prompt.PromptSupport.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import world.willfrog.racing.idl.*;
import world.willfrog.racing.server.security.AuthorizedCall;

/**
 * 每次读取都检查当前版本授权；不把缺失版本替换为当前默认。
 */
@Service
@Transactional(readOnly = true)
public class PromptReadService {

    private final PromptRepository repository;

    public PromptReadService(PromptRepository repository) {
        this.repository = repository;
    }

    public PromptVersion getVersion(AuthorizedCall call, PromptVersionRef ref, String expectedPurpose) {
        return getVersion(call, ref, expectedPurpose, false);
    }

    PromptVersion getVersion(AuthorizedCall call, PromptVersionRef ref, String expectedPurpose, boolean lock) {
        targetKey(target(ref));
        read(call, ref.getScope());
        var value = repository.version(ref, lock);
        require(value != null, "PROMPT_VERSION_NOT_FOUND", "指定提示词版本不存在");
        require(value.getAuthorization().getReadableScopesList().contains(own(call)), "FORBIDDEN", "指定提示词版本未授权当前范围读取");
        require(expectedPurpose == null || expectedPurpose.isEmpty() || expectedPurpose.equals(value.getPurpose()), "PROMPT_PURPOSE_MISMATCH", "提示词版本用途与调用要求不一致");
        return value;
    }

    public PromptDetails get(AuthorizedCall call, GetPromptRequest request) {
        read(call, request.getPrompt().getScope());
        id(request.getPrompt().getPromptId());
        var record = repository.prompt(request.getPrompt(), false);
        require(record != null, "PROMPT_NOT_FOUND", "提示词不存在");
        var b = PromptDetails.newBuilder()
                             .setRecord(record);
        if (request.hasVersion()) {
            b.setVersion(getVersion(call, PromptVersionRef.newBuilder().setScope(request.getPrompt().getScope()).setPromptId(request.getPrompt().getPromptId()).setVersion(request.getVersion()).build(), ""));
        }
        return b.build();
    }

    public PromptVersionList getVersions(AuthorizedCall call, ReadPromptVersionsRequest request) {
        var b = PromptVersionList.newBuilder();
        for (var r : request.getVersionsList()) {
            b.addVersions(getVersion(call, r.getVersion(), r.getExpectedPurpose()));
        }
        return b.build();
    }

    public PromptRecordList list(AuthorizedCall call, ListPromptsRequest request) {
        read(call, request.getScope());
        var page = page(request.getPage());
        return PromptRecordList.newBuilder()
                               .addAllPrompts(repository.prompts(request.getScope(), request.getNameFilter(), request.getPurposeFilter(), page.offset(), page.size()))
                               .setPage(page.info(repository.promptCount(request.getScope(), request.getNameFilter(), request.getPurposeFilter())))
                               .build();
    }

    public PromptVersionList listVersions(AuthorizedCall call, ListPromptVersionsRequest request) {
        read(call, request.getPrompt().getScope());
        id(request.getPrompt().getPromptId());
        var page = page(request.getPage());
        var b = PromptVersionList.newBuilder();
        for (var v : repository.versions(request.getPrompt(), page.offset(), page.size())) {
            b.addVersions(getVersion(call, v.getReference(), ""));
        }
        return b.setPage(page.info(repository.versionCount(request.getPrompt())))
                .build();
    }
}
