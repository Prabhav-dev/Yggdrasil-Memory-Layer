package Yggdrasil;

import Yggdrasil.core.BTreeNode;
import Yggdrasil.core.BPlusTree;
import Yggdrasil.core.InternalNode;
import Yggdrasil.core.LeafNode;
import Yggdrasil.core.Page;
import Yggdrasil.core.StorageManager;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * DashboardServer
 * ----------------
 * Zero-dependency monitoring + IoT onboarding surface for the Yggdrasil engine.
 * Uses only com.sun.net.httpserver (bundled with the JDK) — no servlet container,
 * no JSON library, no external framework.
 *
 * TWO MODES:
 *  - Standalone/demo mode: construct with DashboardServer(port, staticRoot). All
 *    telemetry is simulated locally so the dashboard is runnable with zero setup.
 *  - Live mode: construct with the extra (StorageManager, BPlusTree, NioServer)
 *    args. When present, /api/stats and /api/nodes read real values — actual
 *    page count on disk, the real root page, and an actual BFS walk of the live
 *    B+Tree (decrypting pages through the normal StorageManager/PageFactory
 *    path) instead of a canned shape. If a live call throws (e.g. empty tree,
 *    a page mid-write), handlers fall back to simulated values rather than
 *    500'ing the dashboard.
 */
public final class DashboardServer {

    // ---- Config -----------------------------------------------------------

    private final int port;
    private final Path staticRoot;      // directory containing index.html
    private final HttpServer httpServer;

    // Live engine hooks (nullable — absent means "simulate").
    private final StorageManager storageManager;
    private final BPlusTree bTree;
    private final NioServer nioServer;

    // Demo/onboarding constant. In a real deployment this MUST come from a
    // provisioned secret store, not a source-level constant — see note on
    // /api/iot/register below.
    private static final String PROVISIONING_KEY = "1234567890abcdef"; // 16 bytes ASCII

    private static final int MAX_TREE_NODES_RETURNED = 128; // safety bound on the BFS walk

    // ---- Simulated telemetry state (used when live hooks are absent) ------

    private final AtomicInteger simulatedConnections = new AtomicInteger(4);
    private final AtomicLong simulatedPagesWritten = new AtomicLong(142);
    private final AtomicInteger simulatedBtreeHeight = new AtomicInteger(3);

    // ---- Shared state (used in both modes) ---------------------------------

    private final AtomicInteger registeredDevices = new AtomicInteger(0);
    private final AtomicLong nextLogicalPageId = new AtomicLong(1000);

    /** Issued device sessions: logicalPageId -> session record (demo/roster view). */
    private final Map<Long, DeviceSession> deviceSessions = new ConcurrentHashMap<>();

    /** Rolling event feed, newest first, capped so it can't grow unbounded. */
    private final Deque<Event> eventLog = new ConcurrentLinkedDeque<>();
    private static final int MAX_EVENTS = 200;

    private final SecureRandom random = new SecureRandom();

    // ---- Small value types --------------------------------------------------

    private static final class DeviceSession {
        final long logicalPageId;
        final String subkeyHex;
        final String registeredAt;

        DeviceSession(long logicalPageId, String subkeyHex, String registeredAt) {
            this.logicalPageId = logicalPageId;
            this.subkeyHex = subkeyHex;
            this.registeredAt = registeredAt;
        }
    }

    private static final class Event {
        final String timestamp;
        final String type;   // "info" | "success" | "warn" | "error"
        final String message;

        Event(String timestamp, String type, String message) {
            this.timestamp = timestamp;
            this.type = type;
            this.message = message;
        }
    }

    // ---- Construction ---------------------------------------------------------

    /** Standalone/demo mode: no live engine wired in, everything is simulated. */
    public DashboardServer(int port, Path staticRoot) throws IOException {
        this(port, staticRoot, null, null, null);
    }

    /**
     * Live mode. Pass the engine's actual StorageManager / BPlusTree / NioServer
     * instances (all optional — pass null for any you don't have handy) and the
     * dashboard will read real numbers instead of simulating them.
     */
    public DashboardServer(int port, Path staticRoot,
                            StorageManager storageManager, BPlusTree bTree, NioServer nioServer) throws IOException {
        this.port = port;
        this.staticRoot = staticRoot;
        this.storageManager = storageManager;
        this.bTree = bTree;
        this.nioServer = nioServer;

        this.httpServer = HttpServer.create(new InetSocketAddress(port), 0);
        // Small fixed pool so the dashboard can't starve the storage engine's
        // own worker pool if someone hammers it.
        this.httpServer.setExecutor(Executors.newFixedThreadPool(4));

        httpServer.createContext("/", this::handleIndex);
        httpServer.createContext("/api/stats", this::handleStats);
        httpServer.createContext("/api/nodes", this::handleNodes);
        httpServer.createContext("/api/events", this::handleEvents);
        httpServer.createContext("/api/iot/devices", this::handleDeviceRoster);
        httpServer.createContext("/api/iot/register", this::handleIotRegister);

        recordEvent("info", "Dashboard server initialized" + (isLive() ? " (live engine mode)" : " (simulated mode)"));
    }

    public boolean isLive() {
        return storageManager != null;
    }

    public void start() {
        httpServer.start();
        System.out.println("--- Yggdrasil Dashboard listening on http://localhost:" + port
                + " (" + (isLive() ? "live" : "simulated") + " telemetry) ---");
    }

    public void stop() {
        httpServer.stop(0);
    }

    /**
     * Lets other engine components (RequestDispatcher, WalManager, NioServer,
     * etc.) push human-readable events onto the dashboard's live feed without
     * any coupling beyond this one method call, e.g.:
     *
     *   dashboard.recordEvent("success", "Recovered 3 dirty pages from WAL replay");
     *
     * This is entirely additive — nothing elsewhere needs to change for the
     * dashboard to work, but wiring a few of these calls into WalManager.recover()
     * or RequestDispatcher's insert/delete paths turns the feed from decorative
     * into an actual live audit trail for a demo.
     */
    public void recordEvent(String type, String message) {
        eventLog.addFirst(new Event(Instant.now().toString(), type, message));
        while (eventLog.size() > MAX_EVENTS) {
            eventLog.removeLast();
        }
    }

    // ---- Layer 1: static index -------------------------------------------

    private void handleIndex(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange);
            return;
        }
        Path indexFile = staticRoot.resolve("index.html");
        if (!Files.exists(indexFile)) {
            sendJson(exchange, 404, "{\"error\":\"index.html not found\"}");
            return;
        }
        byte[] body = Files.readAllBytes(indexFile);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    // ---- /api/stats ----------------------------------------------

    private void handleStats(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange);
            return;
        }

        int active;
        long pages;
        int height;
        boolean live = false;

        if (isLive()) {
            try {
                active = (nioServer != null) ? nioServer.getActiveConnections() : deviceSessions.size();
                pages = storageManager.getTotalPages();
                height = computeTreeHeightLive();
                live = true;
            } catch (Exception e) {
                // Live read failed (e.g. mid-write, empty DB) — fall back rather than error out.
                active = simulatedConnections.get();
                pages = simulatedPagesWritten.get();
                height = simulatedBtreeHeight.get();
            }
        } else {
            // Nudge the simulated numbers a little each poll so the dashboard feels alive.
            int jitter = random.nextInt(3) - 1; // -1, 0, +1
            active = Math.max(0, simulatedConnections.addAndGet(jitter));
            pages = simulatedPagesWritten.addAndGet(random.nextInt(2)); // pages only grow
            height = simulatedBtreeHeight.get();
        }

        String json = "{"
                + "\"active_connections\":" + active + ","
                + "\"pages_written\":" + pages + ","
                + "\"btree_height\":" + height + ","
                + "\"registered_devices\":" + registeredDevices.get() + ","
                + "\"mode\":\"" + (live ? "live" : "simulated") + "\""
                + "}";
        sendJson(exchange, 200, json);
    }

    /** Walks root-to-leaf down the leftmost spine to measure tree depth. Unpins as it goes. */
    private int computeTreeHeightLive() throws IOException {
        int rootId = bTree != null ? bTree.getRootID() : storageManager.getMetadata().getRootPageID();
        if (rootId <= 0) return 0;

        int depth = 0;
        int pageNum = rootId;
        int guard = 0;
        while (pageNum > 0 && guard++ < 64) {
            Page page = storageManager.getPage(pageNum, pageNum);
            try {
                if (!(page instanceof BTreeNode)) break;
                depth++;
                if (page instanceof InternalNode) {
                    int[] children = ((InternalNode) page).getChildPageNumbers();
                    if (children == null || children.length == 0) break;
                    pageNum = children[0];
                } else {
                    break; // reached a leaf
                }
            } finally {
                storageManager.unpinPage(page.getPageNumber());
            }
        }
        return depth;
    }

    // ---- /api/nodes ----------------------------------------------

    private void handleNodes(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange);
            return;
        }

        List<String> nodeJsonEntries = null;
        if (isLive()) {
            try {
                nodeJsonEntries = walkLiveTree();
            } catch (Exception e) {
                nodeJsonEntries = null; // fall through to mock below
            }
        }
        if (nodeJsonEntries == null || nodeJsonEntries.isEmpty()) {
            nodeJsonEntries = mockTreeShape();
        }

        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < nodeJsonEntries.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(nodeJsonEntries.get(i));
        }
        sb.append("]");
        sendJson(exchange, 200, sb.toString());
    }

    /** Real BFS traversal of the live B+Tree via StorageManager/PageFactory. */
    private List<String> walkLiveTree() throws IOException {
        int rootId = bTree != null ? bTree.getRootID() : storageManager.getMetadata().getRootPageID();
        List<String> out = new ArrayList<>();
        if (rootId <= 0) return out;

        Deque<Integer> queue = new ArrayDeque<>();
        queue.add(rootId);

        while (!queue.isEmpty() && out.size() < MAX_TREE_NODES_RETURNED) {
            int pageNum = queue.poll();
            Page page = storageManager.getPage(pageNum, pageNum);
            try {
                if (!(page instanceof BTreeNode)) continue;
                BTreeNode node = (BTreeNode) page;
                int parentId = node.getParentPageID();

                if (node instanceof InternalNode) {
                    InternalNode internal = (InternalNode) node;
                    int slotCount = internal.getKeys() != null ? internal.getKeys().length : 0;
                    out.add(nodeJson(pageNum, "internal-" + pageNum, "internal", slotCount, parentId));
                    int[] children = internal.getChildPageNumbers();
                    if (children != null) {
                        for (int child : children) {
                            if (child > 0) queue.add(child);
                        }
                    }
                } else if (node instanceof LeafNode) {
                    LeafNode leaf = (LeafNode) node;
                    int slotCount = leaf.getKeys() != null ? leaf.getKeys().length : 0;
                    out.add(nodeJson(pageNum, "leaf-" + pageNum, "leaf", slotCount, parentId));
                }
            } finally {
                storageManager.unpinPage(page.getPageNumber());
            }
        }
        return out;
    }

    private List<String> mockTreeShape() {
        List<String> out = new ArrayList<>();
        out.add(nodeJson(0, "root", "internal", 3, -1));
        out.add(nodeJson(1, "internal-1", "internal", 4, 0));
        out.add(nodeJson(2, "internal-2", "internal", 4, 0));
        out.add(nodeJson(3, "leaf-1", "leaf", 12, 1));
        out.add(nodeJson(4, "leaf-2", "leaf", 9, 1));
        out.add(nodeJson(5, "leaf-3", "leaf", 14, 2));
        out.add(nodeJson(6, "leaf-4", "leaf", 7, 2));
        return out;
    }

    private static String nodeJson(int pageId, String label, String type, int slotCount, int parentPageId) {
        return "{"
                + "\"page_id\":" + pageId + ","
                + "\"label\":\"" + label + "\","
                + "\"type\":\"" + type + "\","
                + "\"slot_count\":" + slotCount + ","
                + "\"parent_page_id\":" + parentPageId
                + "}";
    }

    // ---- /api/events --------------------------------------------------------

    private void handleEvents(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange);
            return;
        }
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (Event e : eventLog) {
            if (!first) sb.append(",");
            first = false;
            sb.append("{")
              .append("\"timestamp\":\"").append(escape(e.timestamp)).append("\",")
              .append("\"type\":\"").append(escape(e.type)).append("\",")
              .append("\"message\":\"").append(escape(e.message)).append("\"")
              .append("}");
        }
        sb.append("]");
        sendJson(exchange, 200, sb.toString());
    }

    // ---- /api/iot/devices -----------------------------------------------------

    private void handleDeviceRoster(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange);
            return;
        }
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (DeviceSession session : deviceSessions.values()) {
            if (!first) sb.append(",");
            first = false;
            // Subkey is masked here — the roster is a monitoring view, not a
            // place to redisplay live session keys.
            sb.append("{")
              .append("\"logical_page_id\":").append(session.logicalPageId).append(",")
              .append("\"subkey_preview\":\"").append(session.subkeyHex, 0, 4).append("…\",")
              .append("\"registered_at\":\"").append(escape(session.registeredAt)).append("\"")
              .append("}");
        }
        sb.append("]");
        sendJson(exchange, 200, sb.toString());
    }

    // ---- /api/iot/register ---------------------------------------

    /**
     * Accepts a POST body like {"auth_key":"1234567890abcdef"}.
     *
     * SECURITY NOTE (please read before shipping this beyond the demo):
     * - This compares against a single hardcoded pre-shared key. That's fine for
     *   a hackathon onboarding flow, but a hardcoded PSK checked with String.equals
     *   is not something you'd want in production: it's the same secret for every
     *   device, it's visible to anyone with the source or the .class file, and
     *   equals() is not constant-time so it leaks timing information about how
     *   many leading bytes matched.
     * - Below I use MessageDigest.isEqual for a constant-time comparison, which
     *   removes the timing side-channel. It does NOT fix the "one key for every
     *   device" problem — for that you'd want per-device provisioning secrets
     *   issued out-of-band, or at least an HMAC over a nonce instead of a raw
     *   shared key sent over the wire.
     * - The "Ascon Subkey" returned here is a locally-generated random 16-byte
     *   session key, not derived from PROVISIONING_KEY, so a leaked mock response
     *   doesn't expose the provisioning secret itself.
     */
    private void handleIotRegister(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange);
            return;
        }

        String body = readBody(exchange);
        String authKey = extractJsonStringField(body, "auth_key");

        if (authKey == null) {
            recordEvent("warn", "IoT registration rejected: malformed payload");
            sendJson(exchange, 400, "{\"error\":\"missing auth_key\"}");
            return;
        }

        boolean valid = constantTimeEquals(authKey, PROVISIONING_KEY);

        if (!valid) {
            recordEvent("error", "IoT registration rejected: invalid pre-shared key");
            sendJson(exchange, 401, "{\"error\":\"unauthorized\"}");
            return;
        }

        long logicalPageId = nextLogicalPageId.getAndIncrement();
        byte[] subkeyBytes = new byte[16]; // Ascon-128 key length
        random.nextBytes(subkeyBytes);
        String subkeyHex = toHex(subkeyBytes);
        String registeredAt = Instant.now().toString();

        deviceSessions.put(logicalPageId, new DeviceSession(logicalPageId, subkeyHex, registeredAt));
        registeredDevices.incrementAndGet();
        recordEvent("success", "IoT device onboarded — assigned logical page " + logicalPageId);

        String json = "{"
                + "\"status\":\"registered\","
                + "\"logical_page_id\":" + logicalPageId + ","
                + "\"ascon_subkey\":\"" + subkeyHex + "\","
                + "\"protocol\":\"raw-tcp\","
                + "\"note\":\"present this logical_page_id on the NioServer TCP connection to bind the session\""
                + "}";
        sendJson(exchange, 200, json);
    }

    private static boolean constantTimeEquals(String a, String b) {
        byte[] ab = a.getBytes(StandardCharsets.UTF_8);
        byte[] bb = b.getBytes(StandardCharsets.UTF_8);
        // MessageDigest.isEqual is specified to run in constant time when the
        // two arrays are the same length, avoiding early-exit timing leaks.
        return MessageDigest.isEqual(ab, bb);
    }

    // ---- Minimal hand-rolled JSON helpers (zero dependencies) ----

    /**
     * Extracts a single top-level string field "key":"value" from a flat JSON
     * object. Intentionally minimal — no nested objects/arrays, escapes only
     * \" — sufficient for the small fixed payload shape this endpoint expects.
     */
    private static String extractJsonStringField(String json, String key) {
        if (json == null) return null;
        String needle = "\"" + key + "\"";
        int keyIdx = json.indexOf(needle);
        if (keyIdx < 0) return null;
        int colonIdx = json.indexOf(':', keyIdx + needle.length());
        if (colonIdx < 0) return null;
        int firstQuote = json.indexOf('"', colonIdx + 1);
        if (firstQuote < 0) return null;
        int cursor = firstQuote + 1;
        StringBuilder value = new StringBuilder();
        while (cursor < json.length()) {
            char c = json.charAt(cursor);
            if (c == '\\' && cursor + 1 < json.length()) {
                value.append(json.charAt(cursor + 1));
                cursor += 2;
                continue;
            }
            if (c == '"') break;
            value.append(c);
            cursor++;
        }
        return value.toString();
    }

    private static String escape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') sb.append('\\').append(c);
            else if (c == '\n') sb.append("\\n");
            else sb.append(c);
        }
        return sb.toString();
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    // ---- Shared HTTP helpers -----------------------------------------------

    private static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream is = exchange.getRequestBody();
             ByteArrayOutputStream buf = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[1024];
            int n;
            while ((n = is.read(chunk)) != -1) {
                buf.write(chunk, 0, n);
            }
            return buf.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    private static void sendMethodNotAllowed(HttpExchange exchange) throws IOException {
        sendJson(exchange, 405, "{\"error\":\"method not allowed\"}");
    }

    // ---- Standalone entry point (run alongside NioServer) ------------------

    public static void main(String[] args) throws IOException {
        int dashboardPort = 8080;
        Path staticRoot = Path.of("web"); // put index.html here, or pass a path via args

        // Demo/simulated mode by default. To run in live mode, construct your
        // real engine objects first and pass them in instead, e.g.:
        //
        //   StorageManager storageManager = new StorageManager(masterKey, "data");
        //   storageManager.boot();
        //   BPlusTree bTree = new BPlusTree(storageManager);
        //   RequestDispatcher dispatcher = new RequestDispatcher(bTree, heapFile);
        //   NioServer nioServer = new NioServer(9090, dispatcher, 4);
        //   nioServer.start();
        //
        //   DashboardServer dashboard = new DashboardServer(
        //       dashboardPort, staticRoot, storageManager, bTree, nioServer);
        //
        // DashboardServer only needs read access to these objects — it doesn't
        // need to run on the same thread or event loop as NioServer.
        DashboardServer dashboard = new DashboardServer(dashboardPort, staticRoot);
        dashboard.start();

        Runtime.getRuntime().addShutdownHook(new Thread(dashboard::stop));
    }
}