package world.willfrog.racing.server.configuration;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import world.willfrog.racing.idl.ExperimentScope;

/** SQL 参数全部绑定；配置修改和取值锁定同一条实验主记录。 */
@Mapper
public interface ExperimentConfigurationMapper {
    String RECORD_COLUMNS = "environment, scope_instance_id, experiment_id, status, revision, draft_revision, "
            + "draft_json::text AS draft_json, published_version, final_json::text AS final_json, "
            + "deleted_at, created_at, updated_at";
    String KEY = "environment = #{scope.environment} AND scope_instance_id = #{scope.scopeInstanceId} "
            + "AND experiment_id = #{experimentId}";
    String VERSION_COLUMNS = "experiment_id, version, configuration_json::text AS configuration_json, "
            + "total_weight, published_by, published_at";

    @Select("SELECT " + RECORD_COLUMNS + " FROM racing_experiment WHERE " + KEY)
    Map<String, Object> find(@Param("scope") ExperimentScope scope, @Param("experimentId") String experimentId);

    @Select("SELECT " + RECORD_COLUMNS + " FROM racing_experiment WHERE " + KEY + " FOR UPDATE")
    Map<String, Object> lockForUpdate(@Param("scope") ExperimentScope scope, @Param("experimentId") String experimentId);

    @Select("SELECT " + RECORD_COLUMNS + " FROM racing_experiment WHERE " + KEY + " FOR SHARE")
    Map<String, Object> lockForShare(@Param("scope") ExperimentScope scope, @Param("experimentId") String experimentId);

    @Insert("""
            INSERT INTO racing_experiment
                (environment, scope_instance_id, experiment_id, name, status, revision, draft_revision,
                 draft_json, published_version, final_json, deleted_at, created_at, updated_at)
            VALUES (#{environment}, #{scope_instance_id}, #{experiment_id}, #{name,jdbcType=VARCHAR},
                    #{status}, #{revision}, #{draft_revision}, CAST(#{draft_json} AS jsonb),
                    #{published_version,jdbcType=BIGINT}, CAST(#{final_json,jdbcType=VARCHAR} AS jsonb),
                    CAST(#{deleted_at,jdbcType=VARCHAR} AS timestamptz),
                    CAST(#{created_at} AS timestamptz), CAST(#{updated_at} AS timestamptz))
            """)
    int insert(Map<String, Object> row);

    @Update("""
            UPDATE racing_experiment SET name = #{name,jdbcType=VARCHAR}, status = #{status},
                revision = #{revision}, draft_revision = #{draft_revision}, draft_json = CAST(#{draft_json} AS jsonb),
                published_version = #{published_version,jdbcType=BIGINT},
                final_json = CAST(#{final_json,jdbcType=VARCHAR} AS jsonb),
                deleted_at = CAST(#{deleted_at,jdbcType=VARCHAR} AS timestamptz),
                updated_at = CAST(#{updated_at} AS timestamptz)
            WHERE environment = #{environment} AND scope_instance_id = #{scope_instance_id}
                AND experiment_id = #{experiment_id} AND revision = #{previous_revision}
            """)
    int update(Map<String, Object> row);

    @Insert("""
            INSERT INTO racing_experiment_version
                (environment, scope_instance_id, experiment_id, version, configuration_json,
                 total_weight, published_by, published_at)
            VALUES (#{environment}, #{scope_instance_id}, #{experiment_id}, #{version},
                CAST(#{configuration_json} AS jsonb), #{total_weight}, #{published_by}, CAST(#{published_at} AS timestamptz))
            """)
    int insertVersion(Map<String, Object> row);

    @Insert("""
            INSERT INTO racing_experiment_operation
                (operation_id, environment, scope_instance_id, experiment_id, action, actor,
                 before_revision, after_revision, configuration_version, detail_json, operated_at)
            VALUES (#{operation_id}, #{environment}, #{scope_instance_id}, #{experiment_id}, #{action}, #{actor},
                #{before_revision}, #{after_revision}, #{configuration_version,jdbcType=BIGINT},
                CAST(#{detail_json} AS jsonb), CAST(#{operated_at} AS timestamptz))
            """)
    int appendOperation(Map<String, Object> row);

    @Select("SELECT " + VERSION_COLUMNS + " FROM racing_experiment_version WHERE " + KEY + " AND version = #{version}")
    Map<String, Object> findVersion(@Param("scope") ExperimentScope scope,
            @Param("experimentId") String experimentId, @Param("version") long version);

    @Select("<script>SELECT " + RECORD_COLUMNS + " FROM racing_experiment "
            + "WHERE environment = #{scope.environment} AND scope_instance_id = #{scope.scopeInstanceId} "
            + "<if test='!includeDeleted'>AND deleted_at IS NULL </if>"
            + "<if test='statuses.size() > 0'>AND status IN "
            + "<foreach collection='statuses' item='status' open='(' separator=',' close=')'>#{status}</foreach></if> "
            + "ORDER BY updated_at DESC, experiment_id LIMIT #{limit} OFFSET #{offset}</script>")
    List<Map<String, Object>> list(@Param("scope") ExperimentScope scope, @Param("statuses") List<String> statuses,
            @Param("includeDeleted") boolean includeDeleted, @Param("offset") int offset, @Param("limit") int limit);

    @Select("SELECT " + VERSION_COLUMNS + " FROM racing_experiment_version WHERE " + KEY
            + " ORDER BY version DESC LIMIT #{limit} OFFSET #{offset}")
    List<Map<String, Object>> listVersions(@Param("scope") ExperimentScope scope,
            @Param("experimentId") String experimentId, @Param("offset") int offset, @Param("limit") int limit);

    @Select("SELECT detail_json::text FROM racing_experiment_operation WHERE " + KEY
            + " ORDER BY after_revision DESC, operation_id LIMIT #{limit} OFFSET #{offset}")
    List<String> listOperations(@Param("scope") ExperimentScope scope,
            @Param("experimentId") String experimentId, @Param("offset") int offset, @Param("limit") int limit);
}
