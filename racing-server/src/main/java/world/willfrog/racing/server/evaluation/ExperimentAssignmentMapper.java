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
}
