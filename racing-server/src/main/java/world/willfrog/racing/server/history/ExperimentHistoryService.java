package world.willfrog.racing.server.history;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import world.willfrog.racing.idl.AssignmentHistoryPage;
import world.willfrog.racing.idl.AssignmentHistoryRow;
import world.willfrog.racing.idl.ExperimentScope;
import world.willfrog.racing.idl.PromptScope;
import world.willfrog.racing.idl.PromptSetVersion;
import world.willfrog.racing.idl.PromptSetVersionRef;
import world.willfrog.racing.idl.PromptVersion;
import world.willfrog.racing.idl.PromptVersionRef;
import world.willfrog.racing.idl.PublishedExperimentConfiguration;
import world.willfrog.racing.idl.ReadAssignmentHistoryRequest;
import world.willfrog.racing.server.configuration.ExperimentConfigurationRepository;
import world.willfrog.racing.server.evaluation.ExperimentAssignmentMapper;
import world.willfrog.racing.server.evaluation.ExperimentAssignmentMapper.HistoryRow;
import world.willfrog.racing.server.prompt.PromptRepository;
import world.willfrog.racing.server.security.DeveloperAuthorization;
import world.willfrog.racing.server.security.RacingServiceException;

/** 按原实例读取已保存的分配、整组定义和单条正文。不展开整组，也不改用当前发布版。 */
@Service
@Transactional(readOnly = true)
public class ExperimentHistoryService {
    private static final String ASSIGNMENT_ID = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-"
            + "[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    private final DeveloperAuthorization developers;
    private final ExperimentAssignmentMapper assignments;
    private final ExperimentConfigurationRepository configurations;
    private final PromptRepository prompts;

    public ExperimentHistoryService(DeveloperAuthorization developers, ExperimentAssignmentMapper assignments,
            ExperimentConfigurationRepository configurations, PromptRepository prompts) {
        this.developers = developers;
        this.assignments = assignments;
        this.configurations = configurations;
        this.prompts = prompts;
    }

    public AssignmentHistoryPage readAssignments(ReadAssignmentHistoryRequest request) {
        String environment = required(request.getEnvironment(), "environment");
        String scopeInstanceId = required(request.getScopeInstanceId(), "scope_instance_id");
        String experimentId = optional(request.getExperimentId(), "experiment_id");
        String ownerNamespace = optional(request.getOwnerNamespace(), "owner_namespace");
        String ownerKind = optional(request.getOwnerKind(), "owner_kind");
        String ownerScopeInstanceId = optional(request.getOwnerScopeInstanceId(), "owner_scope_instance_id");
        String rootId = optional(request.getRootId(), "root_id");
        String assignmentId = optional(request.getAssignmentId(), "assignment_id");
        if (!assignmentId.isEmpty() && !assignmentId.matches(ASSIGNMENT_ID)) {
            throw RacingServiceException.failure("INVALID_ARGUMENT", "分配编号格式错误");
        }
        int offset = offset(request.getOffset());
        int limit = limit(request.getLimit());
        developers.authorize(DeveloperAuthorization.READ_ASSIGNMENT_HISTORY, environment, scopeInstanceId);
        AssignmentHistoryPage.Builder page = AssignmentHistoryPage.newBuilder();
        for (HistoryRow row : assignments.listByScope(environment, scopeInstanceId, experimentId, ownerNamespace,
                ownerKind, ownerScopeInstanceId, rootId, assignmentId, offset, limit)) {
            page.addRows(toRow(row));
        }
        return page.build();
    }

    public PromptSetVersion readPromptSetVersion(String environment, String scopeInstanceId, String promptSetId,
            long version) {
        environment = required(environment, "environment");
        scopeInstanceId = required(scopeInstanceId, "scope_instance_id");
        promptSetId = required(promptSetId, "prompt_set_id");
        if (version <= 0) throw RacingServiceException.failure("INVALID_ARGUMENT", "集合版本需要是已保存的明确版本");
        developers.authorize(DeveloperAuthorization.READ_PROMPT_SET_VERSION_HISTORY, environment, scopeInstanceId);
        PromptSetVersion stored = prompts.setVersion(PromptSetVersionRef.newBuilder()
                .setScope(PromptScope.newBuilder().setEnvironment(environment).setScopeInstanceId(scopeInstanceId))
                .setPromptSetId(promptSetId).setVersion(version).build(), false);
        if (stored == null) {
            throw RacingServiceException.failure("PROMPT_SET_VERSION_NOT_FOUND", "指定集合版本不存在");
        }
        return stored;
    }

    public PromptVersion readPromptVersion(PromptVersionRef ref) {
        if (ref == null || !ref.hasScope()) {
            throw RacingServiceException.failure("INVALID_ARGUMENT", "正文读取缺少原版本引用");
        }
        String environment = required(ref.getScope().getEnvironment(), "environment");
        String scopeInstanceId = required(ref.getScope().getScopeInstanceId(), "scope_instance_id");
        String promptId = required(ref.getPromptId(), "prompt_id");
        if (ref.getVersion() <= 0) {
            throw RacingServiceException.failure("INVALID_ARGUMENT", "正文版本需要是已保存的明确版本");
        }
        developers.authorize(DeveloperAuthorization.READ_PROMPT_VERSION_HISTORY, environment, scopeInstanceId);
        PromptVersion stored = prompts.version(PromptVersionRef.newBuilder()
                .setScope(PromptScope.newBuilder().setEnvironment(environment).setScopeInstanceId(scopeInstanceId))
                .setPromptId(promptId).setVersion(ref.getVersion()).build(), false);
        if (stored == null) {
            throw RacingServiceException.failure("PROMPT_VERSION_NOT_FOUND", "指定提示词版本不存在");
        }
        return stored;
    }

    private AssignmentHistoryRow toRow(HistoryRow row) {
        AssignmentHistoryRow.Builder builder = AssignmentHistoryRow.newBuilder()
                .setAssignmentId(row.assignmentId()).setEnvironment(row.environment())
                .setScopeInstanceId(row.scopeInstanceId()).setOwnerNamespace(row.ownerNamespace())
                .setOwnerKind(row.ownerKind()).setOwnerScopeInstanceId(row.ownerScopeInstanceId())
                .setRootId(row.rootId()).setExperimentId(row.experimentId()).setValueJson(row.valueJson())
                .setSourceKind(row.sourceKind()).setServicePrincipal(row.servicePrincipal())
                .setCreatedAt(row.createdAt());
        if (row.candidateId() != null) builder.setCandidateId(row.candidateId());
        if (row.businessDefaultSourceJson() != null) {
            builder.setBusinessDefaultSourceJson(row.businessDefaultSourceJson());
        }
        if (row.configurationVersion() != null) {
            if (row.configurationVersion() <= 0) {
                throw RacingServiceException.failure("INVALID_STATE", "已保存分配的配置版本无效");
            }
            ExperimentScope scope = ExperimentScope.newBuilder().setEnvironment(row.environment())
                    .setScopeInstanceId(row.scopeInstanceId()).build();
            PublishedExperimentConfiguration configuration = configurations.findVersion(scope, row.experimentId(),
                    row.configurationVersion());
            if (configuration == null) {
                throw RacingServiceException.failure("NOT_FOUND", "分配引用的配置版本不存在");
            }
            builder.setConfigurationVersion(row.configurationVersion()).setConfiguration(configuration);
        }
        return builder.build();
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank() || !value.equals(value.strip())) {
            throw RacingServiceException.failure("INVALID_ARGUMENT", "字段缺失或格式错误：" + field);
        }
        return value;
    }

    private static String optional(String value, String field) {
        if (value == null || value.isEmpty()) return "";
        if (value.isBlank() || !value.equals(value.strip())) {
            throw RacingServiceException.failure("INVALID_ARGUMENT", "字段缺失或格式错误：" + field);
        }
        return value;
    }

    private static int offset(int value) {
        if (value < 0) throw RacingServiceException.failure("INVALID_ARGUMENT", "分页起点不能为负数");
        return value;
    }

    private static int limit(int value) {
        if (value < 0 || value > 200) throw RacingServiceException.failure("INVALID_ARGUMENT", "分页大小需要在1至200之间");
        return value == 0 ? 50 : value;
    }
}
