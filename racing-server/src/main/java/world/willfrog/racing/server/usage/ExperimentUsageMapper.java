package world.willfrog.racing.server.usage;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 同一服务、实验和尝试编号只保留先提交的使用行；反馈按使用行唯一。 */
@Mapper
public interface ExperimentUsageMapper {
    String USAGE_COLUMNS = """
            usage_id::text AS "usageId", service_principal AS "servicePrincipal",
            environment, scope_instance_id AS "scopeInstanceId",
            owner_namespace AS "ownerNamespace", owner_kind AS "ownerKind",
            owner_scope_instance_id AS "ownerScopeInstanceId", root_id AS "rootId",
            root_run_id AS "rootRunId", current_run_id AS "currentRunId", call_id AS "callId",
            experiment_id AS "experimentId", usage_attempt_id::text AS "usageAttemptId",
            assignment_id::text AS "assignmentId", source_kind AS "sourceKind",
            configuration_version AS "configurationVersion", adopted_value_json AS "adoptedValueJson",
            differs_from_assignment AS "differsFromAssignment", difference_reason AS "differenceReason",
            safety_default AS "safetyDefault", safety_default_reason AS "safetyDefaultReason",
            content_checksum AS "contentChecksum", content_check_passed AS "contentCheckPassed",
            content_check_reason AS "contentCheckReason",
            to_char(occurred_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') AS "occurredAt"
            """;

    @Insert("""
            INSERT INTO racing_experiment_usage (
                usage_id, service_principal, environment, scope_instance_id, owner_namespace, owner_kind,
                owner_scope_instance_id, root_id, root_run_id, current_run_id, call_id, experiment_id,
                usage_attempt_id, assignment_id, source_kind, configuration_version, adopted_value_json,
                differs_from_assignment, difference_reason, safety_default, safety_default_reason,
                content_checksum, content_check_passed, content_check_reason, occurred_at)
            VALUES (
                CAST(#{row.usageId} AS uuid), #{row.servicePrincipal}, #{row.environment}, #{row.scopeInstanceId},
                #{row.ownerNamespace}, #{row.ownerKind}, #{row.ownerScopeInstanceId}, #{row.rootId},
                #{row.rootRunId}, #{row.currentRunId}, #{row.callId}, #{row.experimentId},
                CAST(#{row.usageAttemptId} AS uuid), CAST(#{row.assignmentId,jdbcType=VARCHAR} AS uuid),
                #{row.sourceKind}, #{row.configurationVersion,jdbcType=BIGINT}, #{row.adoptedValueJson},
                #{row.differsFromAssignment}, #{row.differenceReason,jdbcType=VARCHAR}, #{row.safetyDefault},
                #{row.safetyDefaultReason,jdbcType=VARCHAR}, #{row.contentChecksum,jdbcType=VARCHAR},
                #{row.contentCheckPassed,jdbcType=BOOLEAN}, #{row.contentCheckReason,jdbcType=VARCHAR},
                CAST(#{row.occurredAt} AS timestamptz))
            ON CONFLICT (service_principal, experiment_id, usage_attempt_id) DO NOTHING
            """)
    int insertIfAbsent(@Param("row") UsageInsert row);

    @Select("SELECT " + USAGE_COLUMNS + """
             FROM racing_experiment_usage
             WHERE service_principal = #{principal} AND experiment_id = #{experimentId}
               AND usage_attempt_id = CAST(#{attemptId} AS uuid)
             FOR UPDATE
            """)
    UsageRow lock(@Param("principal") String principal, @Param("experimentId") String experimentId,
            @Param("attemptId") String attemptId);

    @Select("SELECT " + USAGE_COLUMNS + """
             FROM racing_experiment_usage
             WHERE service_principal = #{principal} AND experiment_id = #{experimentId}
               AND usage_attempt_id = CAST(#{attemptId} AS uuid)
            """)
    UsageRow find(@Param("principal") String principal, @Param("experimentId") String experimentId,
            @Param("attemptId") String attemptId);

    @Select("""
            SELECT feedback_id::text AS "feedbackId", result_json::text AS "resultJson"
            FROM racing_experiment_result_feedback
            WHERE usage_id = CAST(#{usageId} AS uuid)
            """)
    FeedbackRow findFeedback(@Param("usageId") String usageId);

    @Insert("""
            INSERT INTO racing_experiment_result_feedback (feedback_id, usage_id, result_json)
            VALUES (CAST(#{feedbackId} AS uuid), CAST(#{usageId} AS uuid), CAST(#{resultJson} AS jsonb))
            ON CONFLICT (usage_id) DO NOTHING
            """)
    int insertFeedbackIfAbsent(@Param("feedbackId") String feedbackId, @Param("usageId") String usageId,
            @Param("resultJson") String resultJson);

    record UsageInsert(String usageId, String servicePrincipal, String environment, String scopeInstanceId,
            String ownerNamespace, String ownerKind, String ownerScopeInstanceId, String rootId, String rootRunId,
            String currentRunId, String callId, String experimentId, String usageAttemptId, String assignmentId,
            String sourceKind, Long configurationVersion, String adoptedValueJson, boolean differsFromAssignment,
            String differenceReason, boolean safetyDefault, String safetyDefaultReason, String contentChecksum,
            Boolean contentCheckPassed, String contentCheckReason, String occurredAt) {
    }

    record UsageRow(String usageId, String servicePrincipal, String environment, String scopeInstanceId,
            String ownerNamespace, String ownerKind, String ownerScopeInstanceId, String rootId, String rootRunId,
            String currentRunId, String callId, String experimentId, String usageAttemptId, String assignmentId,
            String sourceKind, Long configurationVersion, String adoptedValueJson, boolean differsFromAssignment,
            String differenceReason, boolean safetyDefault, String safetyDefaultReason, String contentChecksum,
            Boolean contentCheckPassed, String contentCheckReason, String occurredAt) {
    }

    record FeedbackRow(String feedbackId, String resultJson) {
    }
}
