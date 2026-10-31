package world.willfrog.racing.server.result;

import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 使用行按获准范围读取；反馈按使用行唯一。 */
@Mapper
public interface ExperimentResultMapper {
    String ROW_COLUMNS = """
            u.usage_id::text AS "usageId", u.assignment_id::text AS "assignmentId",
            u.environment, u.scope_instance_id AS "scopeInstanceId", u.experiment_id AS "experimentId",
            u.owner_namespace AS "ownerNamespace", u.owner_kind AS "ownerKind",
            u.owner_scope_instance_id AS "ownerScopeInstanceId", u.root_id AS "rootId",
            u.source_kind AS "sourceKind", u.configuration_version AS "configurationVersion",
            u.adopted_value_json AS "adoptedValueJson", u.root_run_id AS "rootRunId",
            u.current_run_id AS "currentRunId", u.call_id AS "callId",
            to_char(u.occurred_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') AS "recordedAt",
            u.content_checksum AS "contentChecksum", f.result_json::text AS "resultJson"
            """;

    @Select("""
            <script>
            """ + "SELECT " + ROW_COLUMNS + """
            FROM racing_experiment_usage u
            LEFT JOIN racing_experiment_result_feedback f ON f.usage_id = u.usage_id
            WHERE u.environment = #{environment}
              AND u.scope_instance_id = #{scopeInstanceId}
            <if test="experimentId != ''">AND u.experiment_id = #{experimentId}</if>
            <if test="configurationVersion != null">AND u.configuration_version = #{configurationVersion}</if>
            <if test="adoptedValueJson != ''">AND u.adopted_value_json = #{adoptedValueJson}</if>
            ORDER BY u.occurred_at, u.call_id
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<ResultRow> query(@Param("environment") String environment, @Param("scopeInstanceId") String scopeInstanceId,
            @Param("experimentId") String experimentId, @Param("configurationVersion") Long configurationVersion,
            @Param("adoptedValueJson") String adoptedValueJson, @Param("limit") int limit,
            @Param("offset") int offset);

    @Select("SELECT " + ROW_COLUMNS + """
             FROM racing_experiment_usage u
             LEFT JOIN racing_experiment_result_feedback f ON f.usage_id = u.usage_id
             WHERE u.usage_id = CAST(#{usageId} AS uuid)
               AND u.environment = #{environment}
               AND u.scope_instance_id = #{scopeInstanceId}
             FOR UPDATE OF u
            """)
    ResultRow lockUsage(@Param("environment") String environment, @Param("scopeInstanceId") String scopeInstanceId,
            @Param("usageId") String usageId);

    @Select("""
            SELECT feedback_id::text AS "feedbackId", result_json::text AS "resultJson"
            FROM racing_experiment_result_feedback
            WHERE usage_id = CAST(#{usageId} AS uuid)
            FOR UPDATE
            """)
    FeedbackRow lockFeedback(@Param("usageId") String usageId);

    @Insert("""
            INSERT INTO racing_experiment_result_feedback (feedback_id, usage_id, result_json)
            VALUES (CAST(#{feedbackId} AS uuid), CAST(#{usageId} AS uuid), CAST(#{resultJson} AS jsonb))
            """)
    int insertFeedback(@Param("feedbackId") String feedbackId, @Param("usageId") String usageId,
            @Param("resultJson") String resultJson);

    @Update("""
            UPDATE racing_experiment_result_feedback
            SET result_json = CAST(#{resultJson} AS jsonb)
            WHERE usage_id = CAST(#{usageId} AS uuid)
            """)
    int updateFeedback(@Param("usageId") String usageId, @Param("resultJson") String resultJson);

    @Delete("""
            DELETE FROM racing_experiment_result_feedback
            WHERE usage_id = CAST(#{usageId} AS uuid)
            """)
    int deleteFeedback(@Param("usageId") String usageId);

    @Select("""
            <script>
            SELECT experiment_id AS "experimentId", configuration_version AS "configurationVersion",
                   count(*) AS "assignmentCount"
            FROM racing_experiment_assignment
            WHERE environment = #{environment}
              AND scope_instance_id = #{scopeInstanceId}
            <if test="experimentId != ''">AND experiment_id = #{experimentId}</if>
            <if test="configurationVersion != null">AND configuration_version = #{configurationVersion}</if>
            GROUP BY experiment_id, configuration_version
            </script>
            """)
    List<AssignmentCount> countAssignments(@Param("environment") String environment,
            @Param("scopeInstanceId") String scopeInstanceId, @Param("experimentId") String experimentId,
            @Param("configurationVersion") Long configurationVersion);

    @Select("""
            <script>
            SELECT u.experiment_id AS "experimentId", u.configuration_version AS "configurationVersion",
                   u.adopted_value_json AS "adoptedValueJson",
                   count(*) AS "usageCount",
                   count(*) FILTER (WHERE f.usage_id IS NULL) AS "missingCount",
                   count(*) FILTER (WHERE u.source_kind &lt;&gt; 'ENDED_FIXED') AS "sampleCount"
            FROM racing_experiment_usage u
            LEFT JOIN racing_experiment_result_feedback f ON f.usage_id = u.usage_id
            WHERE u.environment = #{environment}
              AND u.scope_instance_id = #{scopeInstanceId}
            <if test="experimentId != ''">AND u.experiment_id = #{experimentId}</if>
            <if test="configurationVersion != null">AND u.configuration_version = #{configurationVersion}</if>
            <if test="adoptedValueJson != ''">AND u.adopted_value_json = #{adoptedValueJson}</if>
            GROUP BY u.experiment_id, u.configuration_version, u.adopted_value_json
            </script>
            """)
    List<UsageCount> countUsage(@Param("environment") String environment,
            @Param("scopeInstanceId") String scopeInstanceId, @Param("experimentId") String experimentId,
            @Param("configurationVersion") Long configurationVersion,
            @Param("adoptedValueJson") String adoptedValueJson);

    record ResultRow(String usageId, String assignmentId, String environment, String scopeInstanceId,
            String experimentId, String ownerNamespace, String ownerKind, String ownerScopeInstanceId, String rootId,
            String sourceKind, Long configurationVersion, String adoptedValueJson, String rootRunId,
            String currentRunId, String callId, String recordedAt, String contentChecksum, String resultJson) {
    }

    record FeedbackRow(String feedbackId, String resultJson) {
    }

    record AssignmentCount(String experimentId, Long configurationVersion, long assignmentCount) {
    }

    record UsageCount(String experimentId, Long configurationVersion, String adoptedValueJson, long usageCount,
            long missingCount, long sampleCount) {
    }
}
