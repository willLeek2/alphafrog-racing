package world.willfrog.racing.server.input;

import java.time.OffsetDateTime;

/** 保存数据库中的实际输入，正文保留原始字符串。 */
public record ModelInputRow(String recordId, String servicePrincipal, String environment,
        String scopeInstanceId, String ownerNamespace, String ownerKind, String ownerScopeInstanceId,
        String rootId, String currentRunId, String logicalCallId, String attemptId, String endpointName,
        String bodyJson, long utf8Length, String bodySha256, String contextJson, String sourcesJson,
        OffsetDateTime createdAt) {
    @Override
    public String toString() {
        return "ModelInputRow[recordId=" + recordId + ", utf8Length=" + utf8Length + "]";
    }
}
