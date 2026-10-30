package world.willfrog.racing.server.prompt;

import static world.willfrog.racing.server.prompt.PromptSupport.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import world.willfrog.racing.idl.*;
import world.willfrog.racing.server.security.AuthorizedCall;

/**
 * 核对模型输入声称使用的版本与原根选择；仅在本次保存内复用已核对记录。
 */
@Service
@Transactional(readOnly = true)
public class PromptSourceService {

    private final PromptReadService reads;

    private final PromptSetService sets;

    private final PromptSelectionService selections;

    public PromptSourceService(PromptReadService reads, PromptSetService sets, PromptSelectionService selections) {
        this.reads = reads;
        this.sets = sets;
        this.selections = selections;
    }

    public void validateInstructionSource(AuthorizedCall call, PromptInstructionSource source) {
        validateInstructionSources(call, List.of(source));
    }

    public void validateInstructionSources(AuthorizedCall call, List<PromptInstructionSource> sources) {
        Map<String, PromptSelection> selected = new HashMap<>();
        Map<PromptSetVersionRef, PromptSetVersion> setVersions = new HashMap<>();
        Map<PromptVersionRef, PromptVersion> versions = new HashMap<>();
        for (PromptInstructionSource source : sources) {
            PromptVersion actual = versions.computeIfAbsent(source.getVersion(), ref -> reads.getVersion(call, ref, ""));
            PromptSelection selection = null;
            if (!source.getSelectionId().isEmpty() || !source.getSelectionKey().isEmpty()) {
                String locator = source.getSelectionId()
                                       .isEmpty() ? "key:" + source.getSelectionKey() : "id:" + source.getSelectionId();
                selection = selected.get(locator);
                if (selection == null) {
                    GetPromptSelectionRequest.Builder request = GetPromptSelectionRequest.newBuilder()
                                                                                         .setContext(call.context());
                    if (!source.getSelectionId().isEmpty()) {
                        request.setSelectionId(source.getSelectionId());
                    } else {
                        request.setSelectionKey(source.getSelectionKey());
                    }
                    selection = selections.getStoredForSource(call, request.build());
                    selected.put("id:" + selection.getSelectionId(), selection);
                    selected.put("key:" + selection.getSelectionKey(), selection);
                    // 即使本次使用实验正文，原根绑定的集合也必须仍可读取。
                    PromptTargetRef target = selection.getSelectedTarget();
                    if (target.hasPromptVersion()) {
                        versions.computeIfAbsent(target.getPromptVersion(), ref -> reads.getVersion(call, ref, ""));
                    } else if (target.hasPromptSetVersion()) {
                        setVersions.computeIfAbsent(target.getPromptSetVersion(), ref -> sets.readVersion(call, ref, versions));
                    } else {
                        throw error("STORAGE_ERROR", "选择缺少具体版本");
                    }
                }
                if (!source.getSelectionKey().isEmpty()) {
                    require(source.getSelectionKey().equals(selection.getSelectionKey()), "PROMPT_SOURCE_MISMATCH", "输入来源选择键与持久选择不一致");
                }
            }
            boolean experiment = source.hasExperiment();
            if (source.hasPromptSetVersion()) {
                require(selection != null && selection.getSelectedTarget()
                                                      .hasPromptSetVersion() && selection.getSelectedTarget()
                                                                                         .getPromptSetVersion()
                                                                                         .equals(source.getPromptSetVersion()), "PROMPT_SOURCE_MISMATCH", "集合来源缺少同根选择或与选择不一致");
                PromptSetVersion set = setVersions.computeIfAbsent(source.getPromptSetVersion(), ref -> sets.readVersion(call, ref, versions));
                text(source.getFragmentName(), "fragment_name");
                PromptSetMember member = set.getMembersList()
                                            .stream()
                                            .filter(m -> m.getFragmentName()
                                                          .equals(source.getFragmentName()))
                                            .findFirst()
                                            .orElseThrow(() -> error("PROMPT_SOURCE_MISMATCH", "集合中没有该片段"));
                require(member.getExpectedPurpose().equals(actual.getPurpose()), "PROMPT_PURPOSE_MISMATCH", "提示词版本用途与集合要求不一致");
                if (!experiment) {
                    require(member.getVersion().equals(source.getVersion()), "PROMPT_SOURCE_MISMATCH", "普通来源版本与集合成员不一致");
                }
            } else {
                require(source.getFragmentName().isEmpty(), "PROMPT_SOURCE_MISMATCH", "片段来源缺少集合版本");
                if (selection != null && !experiment) {
                    require(selection.getSelectedTarget()
                                     .hasPromptVersion() && selection.getSelectedTarget()
                                                                     .getPromptVersion()
                                                                     .equals(source.getVersion()), "PROMPT_SOURCE_MISMATCH", "普通来源版本与根选择不一致");
                }
            }
            if (source.hasOrdinaryDefaultSource()) {
                require(!experiment && selection != null && source.getOrdinaryDefaultSource()
                                                                  .equals(selection.getOrigin().getValueSource()), "PROMPT_SOURCE_MISMATCH", "普通默认来源与原选择不一致");
            }
        }
    }
}
