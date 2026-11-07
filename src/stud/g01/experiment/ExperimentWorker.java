package stud.g01.experiment;

import core.problem.State;
import core.solver.algorithm.heuristic.HeuristicType;
import core.solver.algorithm.heuristic.Predictor;
import core.solver.algorithm.searcher.AbstractSearcher;
import core.solver.queue.Node;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import stud.g01.problem.npuzzle.NPuzzleProblem;
import stud.g01.problem.npuzzle.PuzzleBoard;
import stud.g01.runner.PuzzleFeeder;

/** Persistent, line-oriented experiment process. Project diagnostic output goes to stderr. */
public final class ExperimentWorker {
    private static final PrintStream PROTOCOL = System.out;
    private static final long PID = ProcessHandle.current().pid();
    private static final PuzzleFeeder FEEDER = new PuzzleFeeder();
    private static final com.sun.management.OperatingSystemMXBean OS =
            ManagementFactory.getOperatingSystemMXBean()
                            instanceof com.sun.management.OperatingSystemMXBean bean
                    ? bean
                    : null;
    private static final List<GarbageCollectorMXBean> GC =
            ManagementFactory.getGarbageCollectorMXBeans();

    private static long epochNs() {
        Instant now = Instant.now();
        return now.getEpochSecond() * 1_000_000_000L + now.getNano();
    }

    private static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') out.append('\\').append(c);
            else if (c < 32) out.append(String.format("\\u%04x", (int) c));
            else out.append(c);
        }
        return out.append('"').toString();
    }

    private static void emit(String json) {
        PROTOCOL.println(json);
        PROTOCOL.flush();
    }

    private static String field(String line, String key) {
        Matcher matcher =
                Pattern.compile(
                                "\\\""
                                        + key
                                        + "\\\"\\s*:\\s*(\\\"(?:[^\\\"\\\\]|\\\\.)*\\\"|-?[0-9]+|\\[[^\\]]*\\])")
                        .matcher(line);
        if (!matcher.find()) throw new IllegalArgumentException("missing " + key);
        String value = matcher.group(1);
        if (value.startsWith("\"")) {
            // Protocol identifiers and enum names use plain ASCII.
            value = value.substring(1, value.length() - 1);
            if (value.indexOf('\\') >= 0) throw new IllegalArgumentException("escaped " + key);
        }
        return value;
    }

    private static byte[] board(String line, int size) {
        String raw = field(line, "board");
        if (!raw.startsWith("[")) throw new IllegalArgumentException("board must be an array");
        String[] cells = raw.substring(1, raw.length() - 1).split(",");
        if (cells.length != size * size) throw new IllegalArgumentException("board size");
        byte[] result = new byte[cells.length];
        boolean[] seen = new boolean[cells.length];
        for (int i = 0; i < cells.length; i++) {
            int v = Integer.parseInt(cells[i].trim());
            if (v < 0 || v >= cells.length || seen[v])
                throw new IllegalArgumentException("invalid board");
            seen[v] = true;
            result[i] = (byte) v;
        }
        return result;
    }

    private static PuzzleBoard goal(int size) {
        byte[] cells = new byte[size * size];
        for (int i = 0; i < cells.length - 1; i++) cells[i] = (byte) (i + 1);
        return new PuzzleBoard(cells, size);
    }

    private static AbstractSearcher searcher(String algorithm, HeuristicType heuristic, int size) {
        Predictor predictor = FEEDER.getPredictor(heuristic);
        if (size == 4 && algorithm.equals("nida") && heuristic == HeuristicType.DISJOINT_PATTERN) {
            Predictor regular = predictor;
            predictor = (state, goal) -> regular.heuristics(state, goal);
        }
        return FEEDER.getSearcher(algorithm, predictor);
    }

    private static void warmup() {
        for (int size : new int[] {3, 4}) {
            byte[] cells = goal(size).cells();
            int last = cells.length - 1;
            cells[last] = cells[last - 1];
            cells[last - 1] = 0;
            NPuzzleProblem problem =
                    new NPuzzleProblem(new PuzzleBoard(cells, size), goal(size), size);
            for (String algorithm : new String[] {"astar", "ida", "nida"}) {
                for (HeuristicType heuristic :
                        new HeuristicType[] {
                            HeuristicType.MISPLACED,
                            HeuristicType.MANHATTAN,
                            HeuristicType.DISJOINT_PATTERN
                        }) {
                    if (searcher(algorithm, heuristic, size).search(problem) == null)
                        throw new IllegalStateException("warmup failed");
                }
            }
        }
    }

    private static boolean valid(NPuzzleProblem problem, PuzzleBoard initial, Deque<Node> path) {
        State previous = initial;
        for (Node node : path) {
            if (node.getAction() == null || !problem.applicable(previous, node.getAction()))
                return false;
            State next = previous.next(node.getAction());
            if (!next.equals(node.getState())) return false;
            previous = next;
        }
        return problem.goal(previous);
    }

    private static long gc(boolean time) {
        long sum = 0;
        for (GarbageCollectorMXBean bean : GC) {
            long value = time ? bean.getCollectionTime() : bean.getCollectionCount();
            if (value < 0) return -1;
            sum += value;
        }
        return sum;
    }

    private static String number(long value) {
        return value < 0 ? "null" : Long.toString(value);
    }

    private static long delta(long before, long after) {
        return before < 0 || after < 0 ? -1 : after - before;
    }

    private static long procValue(String text, String key) {
        for (String row : text.split("\n")) {
            if (row.startsWith(key + ":")) {
                return Long.parseLong(row.substring(key.length() + 1).trim().split("\\s+")[0])
                        * 1024;
            }
        }
        throw new IllegalStateException("missing /proc memory field: " + key);
    }

    private static long rssBoundary() throws java.io.IOException {
        return procValue(Files.readString(Path.of("/proc/self/smaps_rollup")), "Rss");
    }

    private static void resetRssPeak() throws java.io.IOException {
        Files.writeString(Path.of("/proc/self/clear_refs"), "5\n");
    }

    private static long rssPeak() throws java.io.IOException {
        return procValue(Files.readString(Path.of("/proc/self/status")), "VmHWM");
    }

    private static void handle(String line) {
        String runId;
        try {
            runId = field(line, "run_id");
        } catch (RuntimeException e) {
            emit(
                    "{\"event\":\"result\",\"run_id\":null,\"status\":\"error\",\"end_epoch_ns\":"
                            + epochNs()
                            + ",\"error\":"
                            + quote(e.toString())
                            + "}");
            return;
        }
        boolean started = false;
        try {
            int size = Integer.parseInt(field(line, "size"));
            if (size != 3 && size != 4) throw new IllegalArgumentException("unsupported size");
            String algorithm = field(line, "algorithm");
            if (!List.of("astar", "ida", "nida").contains(algorithm))
                throw new IllegalArgumentException("invalid algorithm");
            HeuristicType heuristic = HeuristicType.valueOf(field(line, "heuristic"));
            int depth = Integer.parseInt(field(line, "depth"));
            long timeoutNanos = Long.parseLong(field(line, "timeout_ns"));
            if (timeoutNanos <= 0) throw new IllegalArgumentException("invalid timeout");
            Integer.parseInt(field(line, "repetition"));
            PuzzleBoard initial = new PuzzleBoard(board(line, size), size);
            NPuzzleProblem problem = new NPuzzleProblem(initial, goal(size), size);
            AbstractSearcher searcher = searcher(algorithm, heuristic, size);
            long rssStart = rssBoundary();
            long gcCount = gc(false), gcTime = gc(true);
            long cpu = OS == null ? -1 : OS.getProcessCpuTime();
            emit(
                    "{\"event\":\"start\",\"run_id\":"
                            + quote(runId)
                            + ",\"pid\":"
                            + PID
                            + ",\"start_epoch_ns\":"
                            + epochNs()
                            + "}");
            started = true;
            resetRssPeak();
            long startEpoch = epochNs();
            long start = System.nanoTime();
            searcher.setDeadlineNanos(start + timeoutNanos);
            Deque<Node> path = null;
            boolean timedOut = false;
            try {
                path = searcher.search(problem);
            } catch (AbstractSearcher.SearchTimeout timeout) {
                timedOut = true;
            }
            long wall = System.nanoTime() - start;
            timedOut |= wall >= timeoutNanos;
            long endEpoch = epochNs();
            long cpuDelta = delta(cpu, OS == null ? -1 : OS.getProcessCpuTime());
            long gcDelta = delta(gcCount, gc(false)), gcTimeDelta = delta(gcTime, gc(true));
            long peakRss = rssPeak();
            long rssEnd = rssBoundary();
            if (path == null && !timedOut)
                throw new IllegalStateException("search returned no solution");
            boolean valid = !timedOut && valid(problem, initial, path);
            StringBuilder iterations = new StringBuilder("[");
            for (long[] row : searcher.iterations) {
                if (iterations.length() > 1) iterations.append(',');
                iterations
                        .append("{\"bound\":")
                        .append(row[0])
                        .append(",\"expanded\":")
                        .append(row[1])
                        .append(",\"generated\":")
                        .append(row[2])
                        .append(",\"complete\":")
                        .append(row.length < 4 || row[3] == 1)
                        .append('}');
            }
            iterations.append(']');
            emit(
                    "{\"event\":\"result\",\"run_id\":"
                            + quote(runId)
                            + ",\"status\":"
                            + quote(timedOut ? "timeout" : valid ? "success" : "error")
                            + ",\"end_epoch_ns\":"
                            + endEpoch
                            + ",\"search_start_epoch_ns\":"
                            + startEpoch
                            + ",\"wall_ns\":"
                            + wall
                            + ",\"cpu_ns\":"
                            + number(cpuDelta)
                            + ",\"rss_start_bytes\":"
                            + rssStart
                            + ",\"rss_end_bytes\":"
                            + rssEnd
                            + ",\"rss_peak_bytes\":"
                            + Math.max(peakRss, Math.max(rssStart, rssEnd))
                            + ",\"rss_peak_kernel_bytes\":"
                            + peakRss
                            + ",\"rss_peak_method\":\"linux_reset_vm_hwm\""
                            + ",\"heuristic_mode\":"
                            + quote(
                                    size == 4
                                                    && algorithm.equals("nida")
                                                    && heuristic == HeuristicType.DISJOINT_PATTERN
                                            ? "regular_6_6_3_full"
                                            : "native")
                            + ",\"nodes_generated\":"
                            + searcher.nodesGenerated()
                            + ",\"nodes_expanded\":"
                            + searcher.nodesExpanded()
                            + ",\"solution_length\":"
                            + (timedOut ? "null" : path.size())
                            + ",\"path_valid\":"
                            + (timedOut ? "null" : valid)
                            + ",\"depth_delta\":"
                            + (timedOut ? "null" : path.size() - depth)
                            + ",\"peak_frontier\":"
                            + number(searcher.peakFrontier)
                            + ",\"peak_depth\":"
                            + searcher.peakDepth
                            + ",\"iterations\":"
                            + (algorithm.equals("astar") ? "null" : iterations.toString())
                            + ",\"bound_pruned\":"
                            + number(searcher.boundPruned)
                            + ",\"cycle_pruned\":"
                            + number(searcher.cyclePruned)
                            + ",\"duplicate_pruned\":"
                            + number(searcher.duplicatePruned)
                            + ",\"heuristic_evaluations\":"
                            + number(searcher.heuristicEvaluations)
                            + ",\"specialized_evaluations\":"
                            + number(searcher.specializedEvaluations)
                            + ",\"gc_count\":"
                            + number(gcDelta)
                            + ",\"gc_time_ms\":"
                            + number(gcTimeDelta)
                            + "}");
        } catch (OutOfMemoryError e) {
            if (!started)
                emit(
                        "{\"event\":\"start\",\"run_id\":"
                                + quote(runId)
                                + ",\"pid\":"
                                + PID
                                + ",\"start_epoch_ns\":"
                                + epochNs()
                                + "}");
            emit(
                    "{\"event\":\"result\",\"run_id\":"
                            + quote(runId)
                            + ",\"status\":\"oom\",\"end_epoch_ns\":"
                            + epochNs()
                            + ",\"metrics_unavailable\":true}");
            System.exit(2);
        } catch (Exception e) {
            if (!started)
                emit(
                        "{\"event\":\"start\",\"run_id\":"
                                + quote(runId)
                                + ",\"pid\":"
                                + PID
                                + ",\"start_epoch_ns\":"
                                + epochNs()
                                + "}");
            emit(
                    "{\"event\":\"result\",\"run_id\":"
                            + quote(runId)
                            + ",\"status\":\"error\",\"end_epoch_ns\":"
                            + epochNs()
                            + ",\"metrics_unavailable\":true,\"error\":"
                            + quote(e.toString())
                            + "}");
        }
    }

    private static void startSampler(Path path) {
        Thread sampler =
                new Thread(
                        () -> {
                            MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
                            try (BufferedWriter out =
                                    Files.newBufferedWriter(
                                            path,
                                            StandardCharsets.UTF_8,
                                            StandardOpenOption.CREATE,
                                            StandardOpenOption.APPEND)) {
                                while (true) {
                                    MemoryUsage heap = memory.getHeapMemoryUsage(),
                                            nonheap = memory.getNonHeapMemoryUsage();
                                    out.write(
                                            "{\"epoch_ns\":"
                                                    + epochNs()
                                                    + ",\"pid\":"
                                                    + PID
                                                    + ",\"heap_used_bytes\":"
                                                    + heap.getUsed()
                                                    + ",\"heap_committed_bytes\":"
                                                    + heap.getCommitted()
                                                    + ",\"nonheap_used_bytes\":"
                                                    + nonheap.getUsed()
                                                    + ",\"nonheap_committed_bytes\":"
                                                    + nonheap.getCommitted()
                                                    + "}\n");
                                    out.flush();
                                    Thread.sleep(1000);
                                }
                            } catch (Exception e) {
                                e.printStackTrace(System.err);
                            }
                        },
                        "jvm-memory-sampler");
        sampler.setDaemon(true);
        sampler.start();
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 2)
            throw new IllegalArgumentException(
                    "expected JVM memory JSONL path and optional --no-sampler");
        System.setOut(System.err);
        if (args.length == 1) startSampler(Path.of(args[0]));
        else if (!args[1].equals("--no-sampler"))
            throw new IllegalArgumentException("unknown option: " + args[1]);
        warmup();
        resetRssPeak();
        rssBoundary();
        emit("{\"event\":\"ready\",\"pid\":" + PID + "}");
        try (BufferedReader in =
                new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) handle(line);
        }
    }
}
