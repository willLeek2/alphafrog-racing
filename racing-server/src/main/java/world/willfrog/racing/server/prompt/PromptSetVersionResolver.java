package world.willfrog.racing.server.prompt;

import static world.willfrog.racing.server.prompt.PromptSupport.*;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import world.willfrog.racing.idl.PromptSetMember;
import world.willfrog.racing.idl.PromptSetVersion;
import world.willfrog.racing.idl.PromptSetVersionRef;
import world.willfrog.racing.idl.PromptVersion;
import world.willfrog.racing.idl.PromptVersionRef;
import world.willfrog.racing.server.security.AuthorizedCall;

/** 沿固定基准展开本次实际成员；展开结果只供读取，仓储始终保存原始定义。 */
@Service
public class PromptSetVersionResolver {

    private final PromptRepository repository;

    private final PromptReadService reads;

    public PromptSetVersionResolver(PromptRepository repository, PromptReadService reads) {
        this.repository = repository;
        this.reads = reads;
    }

    public PromptSetVersion resolve(AuthorizedCall call, PromptSetVersionRef ref,
                                    Map<PromptVersionRef, PromptVersion> versions) {
        return resolveDefinition(call, stored(call, ref), versions);
    }

    public PromptSetVersion resolveDefinition(AuthorizedCall call, PromptSetVersion definition) {
        return resolveDefinition(call, definition, new HashMap<>());
    }

    public PromptSetVersion resolveDefinition(AuthorizedCall call, PromptSetVersion definition,
                                              Map<PromptVersionRef, PromptVersion> versions) {
        PromptSetVersion effective = expand(call, definition, new HashSet<>());
        for (PromptSetMember member : effective.getMembersList()) {
            PromptVersion version = versions.computeIfAbsent(member.getVersion(), key -> reads.getVersion(call, key, ""));
            require(member.getExpectedPurpose().equals(version.getPurpose()),
                    "PROMPT_PURPOSE_MISMATCH", "提示词版本用途与集合要求不一致");
        }
        return effective;
    }

    private PromptSetVersion stored(AuthorizedCall call, PromptSetVersionRef ref) {
        targetKey(target(ref));
        read(call, ref.getScope());
        PromptSetVersion value = repository.setVersion(ref, false);
        require(value != null, "PROMPT_SET_VERSION_NOT_FOUND", "指定集合版本不存在");
        return value;
    }

    private PromptSetVersion expand(AuthorizedCall call, PromptSetVersion definition,
                                    Set<PromptSetVersionRef> visiting) {
        PromptSetVersionRef ref = definition.getReference();
        targetKey(target(ref));
        read(call, ref.getScope());
        require(visiting.add(ref), "PROMPT_SET_INHERITANCE_CYCLE", "集合版本基准存在循环引用");
        try {
            Map<String, PromptSetMember> members = new LinkedHashMap<>();
            if (definition.hasBaseVersion()) {
                PromptSetVersion base = expand(call, stored(call, definition.getBaseVersion()), visiting);
                for (PromptSetMember member : base.getMembersList()) {
                    members.put(member.getFragmentName(), member);
                }
            } else {
                require(definition.getRemovedFragmentNamesCount() == 0,
                        "INVALID_ARGUMENT", "没有基准版本时不能移除片段");
            }
            Set<String> removed = new HashSet<>();
            for (String name : definition.getRemovedFragmentNamesList()) {
                text(name, "removed_fragment_names");
                require(removed.add(name), "INVALID_ARGUMENT", "移除片段名称不能重复");
                require(members.remove(name) != null, "INVALID_ARGUMENT", "基准版本中没有待移除片段：" + name);
            }
            Set<String> modified = new HashSet<>();
            for (PromptSetMember member : definition.getMembersList()) {
                text(member.getFragmentName(), "fragment_name");
                text(member.getExpectedPurpose(), "expected_purpose");
                targetKey(target(member.getVersion()));
                require(modified.add(member.getFragmentName()), "INVALID_ARGUMENT", "集合片段名称不能重复");
                require(!removed.contains(member.getFragmentName()),
                        "INVALID_ARGUMENT", "同一片段不能同时修改和移除");
                members.put(member.getFragmentName(), member);
            }
            require(!members.isEmpty(), "INVALID_ARGUMENT", "集合至少需要一个片段");
            return definition.toBuilder().clearMembers().addAllMembers(members.values()).build();
        } finally {
            visiting.remove(ref);
        }
    }
}
