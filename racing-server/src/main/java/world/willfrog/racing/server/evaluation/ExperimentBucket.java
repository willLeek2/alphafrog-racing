package world.willfrog.racing.server.evaluation;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Objects;

/**
 * 为根运行首次参与实验计算候选。已有分配必须读取持久记录，不能重新调用本类覆盖。
 *
 * <p>散列输入包含业务创建编号；实际路由标签和构建代际不参与分配。
 * 调用方负责确认配置和候选 JSON 合法，并在保存实际采用的记录后返回业务值。</p>
 */
public final class ExperimentBucket {

    private static final String HASH_DOMAIN = "racing-experiment-assignment-v1";
    private static final BigDecimal HASH_SPACE = new BigDecimal(BigInteger.ONE.shiftLeft(Long.SIZE));

    /** 使用配置比例选择候选，保留候选原始值及标识，不解释业务 JSON。 */
    public Candidate select(EvaluationKey key, Candidate first, Candidate second) {
        Objects.requireNonNull(key, "取值身份不能为空");
        Objects.requireNonNull(first, "第一个候选不能为空");
        Objects.requireNonNull(second, "第二个候选不能为空");
        if (first.id().equals(second.id())) {
            throw new IllegalArgumentException("两个候选标识必须不同");
        }
        BigDecimal totalWeight = first.weight().add(second.weight());
        if (totalWeight.signum() <= 0) {
            throw new IllegalArgumentException("候选比例合计必须大于零");
        }

        // 使用整数散列位置与十进制比例交叉相乘，避免浮点转换和除法舍入。
        BigDecimal position = new BigDecimal(hashPosition(key));
        BigDecimal boundary = HASH_SPACE.multiply(first.weight());
        return position.multiply(totalWeight).compareTo(boundary) < 0 ? first : second;
    }

    private static BigInteger hashPosition(EvaluationKey key) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("当前 Java 环境没有提供 SHA-256", impossible);
        }
        addField(digest, HASH_DOMAIN);
        addField(digest, key.environment());
        addField(digest, key.targetScopeInstanceId());
        addField(digest, key.ownerNamespace());
        addField(digest, key.ownerKind());
        addField(digest, key.ownerScopeInstanceId());
        addField(digest, key.rootId());
        addField(digest, key.experimentId());
        return new BigInteger(1, Arrays.copyOf(digest.digest(), Long.BYTES));
    }

    private static void addField(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + "不能为空");
        if (value.isBlank() || !value.equals(value.strip())) {
            throw new IllegalArgumentException(name + "不能为空或包含首尾空白");
        }
        return value;
    }

    /** 与持久分配的唯一键使用同一组业务身份字段。 */
    public record EvaluationKey(
            String environment,
            String targetScopeInstanceId,
            String ownerNamespace,
            String ownerKind,
            String ownerScopeInstanceId,
            String rootId,
            String experimentId) {

        public EvaluationKey {
            environment = requireText(environment, "环境");
            targetScopeInstanceId = requireText(targetScopeInstanceId, "目标创建编号");
            ownerNamespace = requireText(ownerNamespace, "根归属业务");
            ownerKind = requireText(ownerKind, "根归属类型");
            ownerScopeInstanceId = requireText(ownerScopeInstanceId, "原调用创建编号");
            rootId = requireText(rootId, "根运行编号");
            experimentId = requireText(experimentId, "实验编号");
        }
    }

    /** 两种候选可具有相同内容，标识和来源仍然分别保留。 */
    public record Candidate(String id, String valueJson, BigDecimal weight) {

        public Candidate {
            id = requireText(id, "候选标识");
            Objects.requireNonNull(valueJson, "候选 JSON 不能为空");
            Objects.requireNonNull(weight, "候选比例不能为空");
            if (valueJson.isBlank()) {
                throw new IllegalArgumentException("候选 JSON 不能为空白");
            }
            if (weight.signum() < 0) {
                throw new IllegalArgumentException("候选比例不能小于零");
            }
        }
    }
}
