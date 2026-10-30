package world.willfrog.racing.server.evaluation;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 复用03表的业务唯一键；冲突时保留先提交的分配。 */
@Mapper
public interface ExperimentAssignmentMapper {
    @Select("""
            SELECT assignment_id::text AS "assignmentId", value_json AS "valueJson",
                   configuration_version AS "configurationVersion", candidate_id AS "candidateId",
                   source_kind AS "sourceKind", business_default_source_json AS "businessDefaultSourceJson"
            FROM racing_experiment_assignment
            WHERE environment = #{key.environment} AND scope_instance_id = #{key.targetScopeInstanceId}
              AND owner_namespace = #{key.ownerNamespace} AND owner_kind = #{key.ownerKind}
              AND owner_scope_instance_id = #{key.ownerScopeInstanceId} AND root_id = #{key.rootId}
              AND experiment_id = #{key.experimentId}
            """)
    Assignment find(@Param("key") ExperimentBucket.EvaluationKey key);

    @Insert("""
            INSERT INTO racing_experiment_assignment
                (assignment_id, environment, scope_instance_id, owner_namespace, owner_kind,
                 owner_scope_instance_id, root_id, experiment_id, configuration_version,
                 candidate_id, value_json, source_kind, business_default_source_json, service_principal)
            VALUES (CAST(#{record.assignmentId} AS uuid), #{key.environment}, #{key.targetScopeInstanceId},
                    #{key.ownerNamespace}, #{key.ownerKind}, #{key.ownerScopeInstanceId}, #{key.rootId},
                    #{key.experimentId}, #{record.configurationVersion,jdbcType=BIGINT},
                    #{record.candidateId,jdbcType=VARCHAR}, #{record.valueJson}, #{record.sourceKind},
                    #{record.businessDefaultSourceJson,jdbcType=VARCHAR}, #{principal})
            ON CONFLICT (environment, scope_instance_id, owner_namespace, owner_kind,
                         owner_scope_instance_id, root_id, experiment_id) DO NOTHING
            """)
    int insertIfAbsent(@Param("key") ExperimentBucket.EvaluationKey key,
            @Param("record") Assignment record, @Param("principal") String principal);

    record Assignment(String assignmentId, String valueJson, Long configurationVersion,
            String candidateId, String sourceKind, String businessDefaultSourceJson) {
        @Override public String toString() { return "Assignment[id=" + assignmentId + "]"; }
    }

    @Select("""
            <script>
            SELECT assignment_id::text AS "assignmentId", environment, scope_instance_id AS "scopeInstanceId",
                   owner_namespace AS "ownerNamespace", owner_kind AS "ownerKind",
                   owner_scope_instance_id AS "ownerScopeInstanceId", root_id AS "rootId",
                   experiment_id AS "experimentId", configuration_version AS "configurationVersion",
                   candidate_id AS "candidateId", value_json AS "valueJson", source_kind AS "sourceKind",
                   business_default_source_json AS "businessDefaultSourceJson",
                   service_principal AS "servicePrincipal", created_at::text AS "createdAt"
            FROM racing_experiment_assignment
            WHERE environment = #{environment} AND scope_instance_id = #{scopeInstanceId}
            <if test="experimentId != null and experimentId != ''">AND experiment_id = #{experimentId}</if>
            <if test="ownerNamespace != null and ownerNamespace != ''">AND owner_namespace = #{ownerNamespace}</if>
            <if test="ownerKind != null and ownerKind != ''">AND owner_kind = #{ownerKind}</if>
            <if test="ownerScopeInstanceId != null and ownerScopeInstanceId != ''">
              AND owner_scope_instance_id = #{ownerScopeInstanceId}</if>
            <if test="rootId != null and rootId != ''">AND root_id = #{rootId}</if>
            <if test="assignmentId != null and assignmentId != ''">
              AND assignment_id = CAST(#{assignmentId} AS uuid)</if>
            ORDER BY created_at, assignment_id
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    java.util.List<HistoryRow> listByScope(@Param("environment") String environment,
            @Param("scopeInstanceId") String scopeInstanceId, @Param("experimentId") String experimentId,
            @Param("ownerNamespace") String ownerNamespace, @Param("ownerKind") String ownerKind,
            @Param("ownerScopeInstanceId") String ownerScopeInstanceId, @Param("rootId") String rootId,
            @Param("assignmentId") String assignmentId, @Param("offset") int offset, @Param("limit") int limit);

    record HistoryRow(String assignmentId, String environment, String scopeInstanceId, String ownerNamespace,
            String ownerKind, String ownerScopeInstanceId, String rootId, String experimentId,
            Long configurationVersion, String candidateId, String valueJson, String sourceKind,
            String businessDefaultSourceJson, String servicePrincipal, String createdAt) {
        @Override public String toString() { return "HistoryRow[id=" + assignmentId + "]"; }
    }
}
