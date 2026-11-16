package world.willfrog.racing.server.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.protobuf.Message;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import world.willfrog.racing.idl.CreatePromptRequest;
import world.willfrog.racing.idl.PromptDetails;
import world.willfrog.racing.idl.PromptDetailsReply;
import world.willfrog.racing.idl.RacingError;
import world.willfrog.racing.idl.RuntimeIdentity;
import world.willfrog.racing.idl.TargetIdentity;

class PromptAdminClientTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SECRET = "super-secret-token";

    @Test
    void successfulSingleModeMatchesBatchReceipt(@TempDir Path dir) throws Exception {
        Path request = writeRequest(dir.resolve("nested/one.json"), "宽基", "lane-a");
        Path batch = dir.resolve("batch.json");
        JSON.writeValue(batch.toFile(), List.of(Map.of("operation", "CreatePrompt", "request", "nested/one.json")));
        Path receipts = dir.resolve("receipts");
        Fake fake = new Fake();

        Captured single = capture(() -> PromptAdminClient.run(new String[]{
                "--operation", "CreatePrompt", "--request", request.toString()}, factory(fake)));
        Captured batched = capture(() -> PromptAdminClient.run(new String[]{
                "--batch", batch.toString(), "--out-dir", receipts.toString()}, factory(fake)));

        assertEquals(0, single.code());
        assertEquals("", single.stderr());
        assertEquals(0, batched.code());
        assertEquals("OK one" + System.lineSeparator(), batched.stdout());
        assertEquals("", batched.stderr());
        assertEquals(single.stdout(), Files.readString(receipts.resolve("one.json")));
        assertFalse(batched.stdout().contains("actualTarget"));
        assertEquals(List.of("宽基", "宽基"), fake.names);
        assertEquals(List.of("lane-a", "lane-a"), fake.routeTags);
        assertEquals(2, fake.opened);

        Captured again = capture(() -> PromptAdminClient.run(new String[]{
                "--batch", batch.toString(), "--out-dir", receipts.toString()}, factory(fake)));
        assertEquals(1, again.code());
        assertEquals("RACING_PROMPT_ADMIN_ERROR=IllegalStateException" + System.lineSeparator(), again.stderr());
        assertEquals(single.stdout(), Files.readString(receipts.resolve("one.json")));
        assertEquals(2, fake.opened);
    }

    @Test
    void itemFailureDoesNotStopLaterItems(@TempDir Path dir) throws Exception {
        Path rejected = writeRequest(dir.resolve("rejected.json"), "reject", "lane-a");
        writeRequest(dir.resolve("accepted.json"), "ok", "lane-b");
        Path batch = dir.resolve("batch.json");
        JSON.writeValue(batch.toFile(), List.of(
                Map.of("operation", "CreatePrompt", "request", "missing.json"),
                Map.of("operation", "CreatePrompt", "request", "rejected.json"),
                Map.of("operation", "CreatePrompt", "request", "accepted.json"),
                Map.of("operation", "CreatePrompt", "request", requestMap("inline", ""))));
        Path receipts = dir.resolve("receipts");
        Fake fake = new Fake();

        Captured singleRejected = capture(() -> PromptAdminClient.run(new String[]{
                "--operation", "CreatePrompt", "--request", rejected.toString()}, factory(fake)));
        Captured batched = capture(() -> PromptAdminClient.run(new String[]{
                "--batch", batch.toString(), "--out-dir", receipts.toString()}, factory(fake)));

        assertEquals(1, batched.code());
        assertEquals("", batched.stderr());
        assertEquals("FAILED missing NoSuchFileException" + System.lineSeparator()
                + "FAILED rejected PROMPT_DEFAULT_NOT_FOUND" + System.lineSeparator()
                + "OK accepted" + System.lineSeparator()
                + "OK 0004" + System.lineSeparator(), batched.stdout());
        assertFalse(Files.exists(receipts.resolve("missing.json")));
        assertEquals(singleRejected.stdout(), Files.readString(receipts.resolve("rejected.json")));
        assertTrue(singleRejected.stdout().contains("PROMPT_DEFAULT_NOT_FOUND"));
        assertEquals("", singleRejected.stderr());
        assertEquals(1, singleRejected.code());
        assertTrue(Files.exists(receipts.resolve("accepted.json")));
        assertTrue(Files.exists(receipts.resolve("0004.json")));
        assertEquals(List.of("reject", "ok", "inline"), fake.names.subList(1, 4));
        assertEquals(List.of("lane-a", "lane-b", ""), fake.routeTags.subList(1, 4));
        assertEquals(2, fake.opened);
    }

    @Test
    void remoteStartupFailureIsRecordedOnce(@TempDir Path dir) throws Exception {
        writeRequest(dir.resolve("one.json"), "one", "lane-a");
        writeRequest(dir.resolve("two.json"), "two", "lane-a");
        Path batch = dir.resolve("batch.json");
        JSON.writeValue(batch.toFile(), List.of(
                Map.of("operation", "CreatePrompt", "request", "one.json"),
                Map.of("operation", "CreatePrompt", "request", "two.json")));
        AtomicInteger opened = new AtomicInteger();
        Captured captured = capture(() -> PromptAdminClient.run(new String[]{
                "--batch", batch.toString(), "--out-dir", dir.resolve("receipts").toString()}, settings -> {
            opened.incrementAndGet();
            throw new IllegalStateException("registry down");
        }));

        assertEquals(1, captured.code());
        assertEquals("", captured.stderr());
        assertEquals(1, opened.get());
        assertEquals("FAILED one IllegalStateException" + System.lineSeparator()
                + "FAILED two IllegalStateException" + System.lineSeparator(), captured.stdout());
        assertFalse(Files.exists(dir.resolve("receipts/one.json")));
        assertFalse(Files.exists(dir.resolve("receipts/two.json")));
    }

    @Test
    void planShapeKeepsExtraFieldsAndUsesKey(@TempDir Path dir) throws Exception {
        Path request = writeRequest(dir.resolve("requests/prompt-0000.json"), "named", "lane-a");
        ObjectNode action = JSON.createObjectNode();
        action.put("operation", "CreatePrompt");
        action.put("requestType", "CreatePromptRequest");
        action.put("request", request.toString());
        action.put("requestRelative", "requests/prompt-0000.json");
        action.put("saveReplyAs", dir.resolve("should-not-write.json").toString());
        action.put("saveReplyRelative", "replies/prompt-0000.json");
        action.put("replyRule", "保留CLI原stdout/stderr/退出码");
        action.put("key", "prompt-0000");
        ObjectNode batchNode = JSON.createObjectNode();
        batchNode.put("stage", "prompts");
        batchNode.set("pendingActions", JSON.createArrayNode().add(action));
        Path batch = dir.resolve("plans/prompts-plan.json");
        Files.createDirectories(batch.getParent());
        JSON.writeValue(batch.toFile(), batchNode);
        Path receipts = dir.resolve("receipts");
        Fake fake = new Fake();

        Captured captured = capture(() -> PromptAdminClient.run(new String[]{
                "--batch", batch.toString(), "--out-dir", receipts.toString()}, factory(fake)));

        assertEquals(0, captured.code());
        assertEquals("OK prompt-0000" + System.lineSeparator(), captured.stdout());
        assertTrue(Files.exists(receipts.resolve("prompt-0000.json")));
        assertFalse(Files.exists(dir.resolve("should-not-write.json")));
        assertEquals(1, fake.opened);
    }

    @Test
    void credentialFieldInBatchIsRejectedAndNotWritten(@TempDir Path dir) throws Exception {
        Map<String, Object> smuggled = new LinkedHashMap<>();
        smuggled.put("operation", "CreatePrompt");
        smuggled.put("request", requestMap("hidden", "lane-a"));
        smuggled.put("token", SECRET);
        Path batch = dir.resolve("batch.json");
        JSON.writeValue(batch.toFile(), List.of(smuggled, Map.of(
                "operation", "CreatePrompt", "request", requestMap("visible", "lane-b"))));
        Path receipts = dir.resolve("receipts");
        Fake fake = new Fake();

        Captured captured = capture(() -> PromptAdminClient.run(new String[]{
                "--batch", batch.toString(), "--out-dir", receipts.toString()}, factory(fake)));

        assertEquals(1, captured.code());
        assertEquals(List.of("visible"), fake.names);
        assertEquals(1, fake.opened);
        assertEquals("FAILED 0001 IllegalArgumentException" + System.lineSeparator()
                + "OK 0002" + System.lineSeparator(), captured.stdout());
        assertFalse(Files.exists(receipts.resolve("0001.json")));
        String written = captured.stdout() + captured.stderr() + Files.readString(receipts.resolve("0002.json"));
        assertFalse(written.contains(SECRET));
    }

    @Test
    void unknownCredentialArgumentIsRejected() throws Exception {
        AtomicInteger opened = new AtomicInteger();
        Captured captured = capture(() -> PromptAdminClient.run(new String[]{
                "--operation", "CreatePrompt", "--request", "request.json", "--token", SECRET}, settings -> {
            opened.incrementAndGet();
            throw new AssertionError("invalid arguments should not open a session");
        }));

        assertEquals(1, captured.code());
        assertEquals("", captured.stdout());
        assertEquals("RACING_PROMPT_ADMIN_ERROR=IllegalArgumentException" + System.lineSeparator(), captured.stderr());
        assertFalse(captured.stderr().contains(SECRET));
        assertEquals(0, opened.get());
    }

    @Test
    void helpMentionsBatchMode() throws Exception {
        Captured captured = capture(() -> PromptAdminClient.run(new String[]{"--help"}, settings -> {
            throw new AssertionError("help should not open a session");
        }));
        assertEquals(0, captured.code());
        assertEquals("", captured.stderr());
        assertTrue(captured.stdout().contains("--operation"));
        assertTrue(captured.stdout().contains("--batch"));
        assertTrue(captured.stdout().contains("--out-dir"));
        assertTrue(captured.stdout().contains("OK <key>"));
    }

    @Test
    void emptyBatchIsRejected(@TempDir Path dir) throws Exception {
        Path batch = dir.resolve("batch.json");
        Files.writeString(batch, "{\"pendingActions\":[]}");
        Path receipts = dir.resolve("receipts");
        AtomicInteger opened = new AtomicInteger();
        Captured captured = capture(() -> PromptAdminClient.run(new String[]{
                "--batch", batch.toString(), "--out-dir", receipts.toString()}, settings -> {
            opened.incrementAndGet();
            throw new AssertionError("empty batch should not open a session");
        }));
        assertEquals(1, captured.code());
        assertEquals("RACING_PROMPT_ADMIN_ERROR=IllegalArgumentException" + System.lineSeparator(), captured.stderr());
        assertFalse(Files.exists(receipts));
        assertEquals(0, opened.get());
    }

    @Test
    void sampleRequestsParse(@TempDir Path dir) throws Exception {
        String rootText = System.getenv("PROMPT_ADMIN_SAMPLES");
        assumeTrue(rootText != null && !rootText.isBlank());
        Path root = Path.of(rootText);
        List<Path> requests;
        try (Stream<Path> listing = Files.list(root.resolve("requests"))) {
            requests = listing.filter(path -> {
                String name = path.getFileName().toString();
                return name.startsWith("prompt-") && name.endsWith(".json");
            }).sorted(Comparator.comparing(path -> path.getFileName().toString())).toList();
        }
        assertEquals(220, requests.size());
        ArrayNode actions = JSON.createArrayNode();
        for (Path request : requests) {
            ObjectNode action = JSON.createObjectNode();
            action.put("operation", "CreatePrompt");
            action.put("requestType", "CreatePromptRequest");
            action.put("request", request.toString());
            action.put("requestRelative", "requests/" + request.getFileName());
            action.put("saveReplyAs", dir.resolve("unused").resolve(request.getFileName()).toString());
            action.put("saveReplyRelative", "replies/" + request.getFileName());
            action.put("replyRule", "保留CLI原stdout/stderr/退出码");
            actions.add(action);
        }
        ObjectNode batchNode = JSON.createObjectNode();
        batchNode.put("stage", "prompts");
        batchNode.set("pendingActions", actions);
        Path batch = dir.resolve("batch.json");
        JSON.writeValue(batch.toFile(), batchNode);
        Path receipts = dir.resolve("receipts");
        Fake fake = new Fake();

        Captured captured = capture(() -> PromptAdminClient.run(new String[]{
                "--batch", batch.toString(), "--out-dir", receipts.toString()}, factory(fake)));

        assertEquals(0, captured.code(), captured.stdout());
        assertEquals("", captured.stderr());
        assertEquals(220, fake.names.size());
        assertEquals(1, fake.opened);
        assertEquals(220, captured.stdout().lines().filter(line -> line.startsWith("OK ")).count());
        assertTrue(Files.isRegularFile(receipts.resolve("prompt-0000.json")));
        assertTrue(Files.isRegularFile(receipts.resolve("prompt-0219.json")));
        assertFalse(captured.stdout().contains("actualTarget"));
        assertFalse(Files.exists(dir.resolve("unused")));
    }

    private static Path writeRequest(Path path, String name, String routeTag) throws Exception {
        Files.createDirectories(path.getParent());
        JSON.writeValue(path.toFile(), requestMap(name, routeTag));
        return path;
    }

    private static Map<String, Object> requestMap(String name, String routeTag) {
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("environment", "beta");
        target.put("targetScopeInstanceId", "scope-1");
        target.put("targetRouteTag", routeTag);
        target.put("targetDeploymentId", "deploy-1");
        target.put("expectedTargetGenerationId", "gen-1");
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("context", Map.of("target", target));
        request.put("name", name);
        return request;
    }

    private static PromptAdminClient.SessionFactory factory(Fake fake) {
        return settings -> {
            fake.opened++;
            return fake;
        };
    }

    private static Captured capture(Callable<Integer> call) throws Exception {
        PrintStream out = System.out;
        PrintStream err = System.err;
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        System.setOut(new PrintStream(outBytes, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(errBytes, true, StandardCharsets.UTF_8));
        try {
            int code = call.call();
            return new Captured(code, outBytes.toString(StandardCharsets.UTF_8), errBytes.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
    }

    private record Captured(int code, String stdout, String stderr) { }

    private static final class Fake implements PromptAdminClient.RemoteSession {
        private final List<String> names = new ArrayList<>();
        private final List<String> routeTags = new ArrayList<>();
        private int opened;

        @Override
        public Message invoke(String operation, Class<? extends Message> type, Message request, TargetIdentity target) {
            CreatePromptRequest create = (CreatePromptRequest) request;
            names.add(create.getName());
            routeTags.add(target.getTargetRouteTag());
            if (create.getName().equals("reject")) {
                return PromptDetailsReply.newBuilder()
                        .setError(RacingError.newBuilder().setCode("PROMPT_DEFAULT_NOT_FOUND").setMessage("该业务尚未设置默认集合").build())
                        .setActualTarget(actualFrom(target))
                        .build();
            }
            return PromptDetailsReply.newBuilder()
                    .setPrompt(PromptDetails.getDefaultInstance())
                    .setActualTarget(actualFrom(target))
                    .build();
        }

        @Override
        public void close() { }

        private static RuntimeIdentity actualFrom(TargetIdentity target) {
            return RuntimeIdentity.newBuilder()
                    .setEnvironment(target.getEnvironment())
                    .setScopeInstanceId(target.getTargetScopeInstanceId())
                    .setRouteTag(target.getTargetRouteTag())
                    .setDeploymentId(target.getTargetDeploymentId())
                    .setActualTargetGenerationId(target.getExpectedTargetGenerationId())
                    .build();
        }
    }
}
