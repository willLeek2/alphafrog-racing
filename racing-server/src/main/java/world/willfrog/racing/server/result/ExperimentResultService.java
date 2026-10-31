package world.willfrog.racing.server.result;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import world.willfrog.racing.idl.CorrectExperimentResultRequest;
import world.willfrog.racing.idl.DeleteExperimentResultRequest;
import world.willfrog.racing.idl.ExperimentResultPage;
import world.willfrog.racing.idl.ExperimentResultRow;
import world.willfrog.racing.idl.ExperimentResultSummary;
import world.willfrog.racing.idl.ExperimentResultSummaryGroup;
import world.willfrog.racing.idl.ExperimentRunMode;
import world.willfrog.racing.idl.ExperimentScope;
import world.willfrog.racing.idl.PublishedExperimentConfiguration;
import world.willfrog.racing.idl.QueryExperimentResultRequest;
import world.willfrog.racing.idl.SummarizeExperimentResultRequest;
import world.willfrog.racing.idl.SupplementExperimentResultRequest;
import world.willfrog.racing.server.configuration.ExperimentConfigurationRepository;
import world.willfrog.racing.server.result.ExperimentResultMapper.AssignmentCount;
import world.willfrog.racing.server.result.ExperimentResultMapper.FeedbackRow;
import world.willfrog.racing.server.result.ExperimentResultMapper.ResultRow;
import world.willfrog.racing.server.result.ExperimentResultMapper.UsageCount;
import world.willfrog.racing.server.security.RacingServiceException;

/**
 * 查询、补充、修正、删除和汇总实验结果。远程入口已经完成开发者授权。
 * 运行方式只读已发布配置。没有版本时返回未知，不用草稿补猜。
 */
@Service
public class ExperimentResultService {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;
    private final ExperimentResultRepository results;
    private final ExperimentConfigurationRepository configurations;

    public ExperimentResultService(ExperimentResultRepository results,
            ExperimentConfigurationRepository configurations) {
        this.results = results;
        this.configurations = configurations;
    }

    @Transactional(readOnly = true)
    public ExperimentResultPage query(QueryExperimentResultRequest request) {
        ScopeFilter filter = filter(request.getEnvironment(), request.getScopeInstanceId(), request.getExperimentId(),
                request.hasConfigurationVersion() ? request.getConfigurationVersion() : null,
                request.getAdoptedValueJson());
        int limit = limit(request.getLimit());
        int offset = offset(request.getOffset());
        ExperimentScope scope = scope(filter);
        Map<String, String> modes = new HashMap<>();
        ExperimentResultPage.Builder page = ExperimentResultPage.newBuilder();
        for (ResultRow row : results.query(filter.environment(), filter.scopeInstanceId(), filter.experimentId(),
                filter.configurationVersion(), filter.adoptedValueJson(), limit, offset)) {
            page.addRows(toRow(row, runMode(scope, row.experimentId(), row.configurationVersion(), modes)));
        }
        return page.build();
    }

    @Transactional
    public void supplement(SupplementExperimentResultRequest request) {
        Located located = locate(request.getEnvironment(), request.getScopeInstanceId(), request.getUsageId());
        JsonNode incoming = objectJson(request.getResultJson());
        FeedbackRow feedback = results.lockFeedback(located.usageId());
        if (feedback == null) {
            results.insertFeedback(UUID.randomUUID().toString(), located.usageId(), request.getResultJson());
            return;
        }
        if (!sameJson(incoming, objectJson(feedback.resultJson()))) {
            throw RacingServiceException.failure("CONFLICT", "结果反馈已存在且内容不同，请改用修正");
        }
    }

    @Transactional
    public void correct(CorrectExperimentResultRequest request) {
        Located located = locate(request.getEnvironment(), request.getScopeInstanceId(), request.getUsageId());
        JsonNode incoming = objectJson(request.getResultJson());
        FeedbackRow feedback = results.lockFeedback(located.usageId());
        if (feedback == null) {
            throw RacingServiceException.failure("NOT_FOUND", "结果反馈不存在");
        }
        if (!sameJson(incoming, objectJson(feedback.resultJson()))) {
            results.updateFeedback(located.usageId(), request.getResultJson());
        }
    }

    @Transactional
    public void delete(DeleteExperimentResultRequest request) {
        Located located = locate(request.getEnvironment(), request.getScopeInstanceId(), request.getUsageId());
        FeedbackRow feedback = results.lockFeedback(located.usageId());
        if (feedback != null) {
            results.deleteFeedback(located.usageId());
        }
    }

    @Transactional(readOnly = true)
    public ExperimentResultSummary summarize(SummarizeExperimentResultRequest request) {
        ScopeFilter filter = filter(request.getEnvironment(), request.getScopeInstanceId(), request.getExperimentId(),
                request.hasConfigurationVersion() ? request.getConfigurationVersion() : null,
                request.getAdoptedValueJson());
        String requestedMode = runModeFilter(request.getRunMode());
        ExperimentScope scope = scope(filter);
        Map<String, String> modes = new HashMap<>();
        Map<VersionKey, Long> assignments = new HashMap<>();
        for (AssignmentCount count : results.countAssignments(filter.environment(), filter.scopeInstanceId(),
                filter.experimentId(), filter.configurationVersion())) {
            assignments.put(new VersionKey(count.experimentId(), count.configurationVersion()), count.assignmentCount());
        }
        ExperimentResultSummary.Builder summary = ExperimentResultSummary.newBuilder();
        for (UsageCount count : results.countUsage(filter.environment(), filter.scopeInstanceId(),
                filter.experimentId(), filter.configurationVersion(), filter.adoptedValueJson())) {
            String mode = runMode(scope, count.experimentId(), count.configurationVersion(), modes);
            if (requestedMode != null && !requestedMode.equals(mode)) {
                continue;
            }
            Long assignmentCount = assignments.get(new VersionKey(count.experimentId(), count.configurationVersion()));
            ExperimentResultSummaryGroup.Builder group = ExperimentResultSummaryGroup.newBuilder()
                    .setExperimentId(count.experimentId())
                    .setAdoptedValueJson(count.adoptedValueJson() == null ? "" : count.adoptedValueJson())
                    .setRunMode(mode)
                    .setAssignmentCount(assignmentCount == null ? 0 : assignmentCount)
                    .setUsageCount(count.usageCount())
                    .setMissingResultCount(count.missingCount())
                    .setSampleCount(count.sampleCount());
            if (count.configurationVersion() != null) {
                group.setConfigurationVersion(count.configurationVersion());
            }
            summary.addGroups(group);
        }
        return summary.build();
    }

    private Located locate(String environment, String scopeInstanceId, String usageId) {
        String scopeEnvironment = token(environment, "环境");
        String scopeId = token(scopeInstanceId, "泳道实例");
        String id = canonicalUuid(usageId);
        ResultRow row = results.lockUsage(scopeEnvironment, scopeId, id);
        if (row == null) {
            throw RacingServiceException.failure("NOT_FOUND", "使用记录不存在");
        }
        return new Located(id);
    }

    private ExperimentResultRow toRow(ResultRow row, String runMode) {
        ExperimentResultRow.Builder builder = ExperimentResultRow.newBuilder()
                .setUsageId(nullToEmpty(row.usageId()))
                .setAssignmentId(nullToEmpty(row.assignmentId()))
                .setEnvironment(nullToEmpty(row.environment()))
                .setScopeInstanceId(nullToEmpty(row.scopeInstanceId()))
                .setExperimentId(nullToEmpty(row.experimentId()))
                .setOwnerNamespace(nullToEmpty(row.ownerNamespace()))
                .setOwnerKind(nullToEmpty(row.ownerKind()))
                .setOwnerScopeInstanceId(nullToEmpty(row.ownerScopeInstanceId()))
                .setRootId(nullToEmpty(row.rootId()))
                .setSourceKind(nullToEmpty(row.sourceKind()))
                .setAdoptedValueJson(nullToEmpty(row.adoptedValueJson()))
                .setRootRunId(nullToEmpty(row.rootRunId()))
                .setCurrentRunId(nullToEmpty(row.currentRunId()))
                .setCallId(nullToEmpty(row.callId()))
                .setRecordedAt(nullToEmpty(row.recordedAt()))
                .setContentChecksum(nullToEmpty(row.contentChecksum()))
                .setResultJson(nullToEmpty(row.resultJson()))
                .setRunMode(runMode);
        if (row.configurationVersion() != null) {
            builder.setConfigurationVersion(row.configurationVersion());
        }
        return builder.build();
    }

    private String runMode(ExperimentScope scope, String experimentId, Long version, Map<String, String> cache) {
        if (version == null || version <= 0 || experimentId == null || experimentId.isBlank()) {
            return "";
        }
        String key = experimentId + "\0" + version;
        String cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        PublishedExperimentConfiguration published = configurations.findVersion(scope, experimentId, version);
        String mode = "";
        if (published != null && published.hasConfiguration()) {
            ExperimentRunMode value = published.getConfiguration().getRunMode();
            if (value == ExperimentRunMode.ONLINE || value == ExperimentRunMode.OFFLINE) {
                mode = value.name();
            }
        }
        cache.put(key, mode);
        return mode;
    }

    private static ScopeFilter filter(String environment, String scopeInstanceId, String experimentId,
            Long configurationVersion, String adoptedValueJson) {
        if (configurationVersion != null && configurationVersion <= 0) {
            throw RacingServiceException.failure("INVALID_ARGUMENT", "配置版本必须大于 0");
        }
        return new ScopeFilter(token(environment, "环境"), token(scopeInstanceId, "泳道实例"),
                optionalToken(experimentId, "实验编号"), configurationVersion,
                optionalToken(adoptedValueJson, "采用值"));
    }

    private static String runModeFilter(String runMode) {
        if (runMode == null || runMode.isBlank()) {
            return null;
        }
        String value = optionalToken(runMode, "运行方式");
        if (!value.equals(ExperimentRunMode.ONLINE.name()) && !value.equals(ExperimentRunMode.OFFLINE.name())) {
            throw RacingServiceException.failure("INVALID_ARGUMENT", "运行方式只能是 ONLINE 或 OFFLINE");
        }
        return value;
    }

    private static int limit(int limit) {
        if (limit == 0) {
            return DEFAULT_LIMIT;
        }
        if (limit < 0 || limit > MAX_LIMIT) {
            throw RacingServiceException.failure("INVALID_ARGUMENT", "分页条数必须在 1 到 200 之间");
        }
        return limit;
    }

    private static int offset(int offset) {
        if (offset < 0) {
            throw RacingServiceException.failure("INVALID_ARGUMENT", "分页偏移不能小于 0");
        }
        return offset;
    }

    private static JsonNode objectJson(String text) {
        JsonNode node = parseJson(text);
        if (node == null || !node.isObject()) {
            throw RacingServiceException.failure("INVALID_ARGUMENT", "结果必须是 JSON 对象");
        }
        return node;
    }

    private static JsonNode parseJson(String text) {
        try (JsonParser parser = JSON.createParser(text)) {
            JsonNode node = JSON.readTree(parser);
            if (parser.nextToken() != null) {
                throw RacingServiceException.failure("INVALID_ARGUMENT", "JSON 后面还有多余内容");
            }
            return node;
        } catch (RacingServiceException failure) {
            throw failure;
        } catch (Exception failure) {
            throw RacingServiceException.failure("INVALID_ARGUMENT", "JSON 内容无效");
        }
    }

    private static boolean sameJson(JsonNode left, JsonNode right) {
        return left.equals(right);
    }

    private static String canonicalUuid(String value) {
        String token = token(value, "使用行编号");
        try {
            return UUID.fromString(token).toString();
        } catch (IllegalArgumentException ex) {
            throw RacingServiceException.failure("INVALID_ARGUMENT", "使用行编号格式无效");
        }
    }

    private static String token(String value, String name) {
        if (value == null || value.isBlank() || !value.equals(value.trim())) {
            throw RacingServiceException.failure("INVALID_ARGUMENT", name + "不能为空或包含首尾空白");
        }
        return value;
    }

    private static String optionalToken(String value, String name) {
        if (value == null || value.isBlank()) {
            return "";
        }
        if (!value.equals(value.trim())) {
            throw RacingServiceException.failure("INVALID_ARGUMENT", name + "不能包含首尾空白");
        }
        return value;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static ExperimentScope scope(ScopeFilter filter) {
        return ExperimentScope.newBuilder().setEnvironment(filter.environment())
                .setScopeInstanceId(filter.scopeInstanceId()).build();
    }

    private record ScopeFilter(String environment, String scopeInstanceId, String experimentId,
            Long configurationVersion, String adoptedValueJson) {
    }

    private record Located(String usageId) {
    }

    private record VersionKey(String experimentId, Long configurationVersion) {
    }
}
