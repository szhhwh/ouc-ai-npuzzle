package core.runner;

import core.problem.Problem;
import core.solver.algorithm.heuristic.Predictor;
import core.solver.algorithm.searcher.AbstractSearcher;
import core.solver.algorithm.searcher.BestFirstSearcher;
import core.solver.algorithm.searcher.IdaStarSearcher;
import core.solver.algorithm.searcher.SearchObserver;
import core.solver.queue.EvaluationType;
import core.solver.queue.Frontier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** A problem module owns its heuristic vocabulary and registers specialized engines here. */
public abstract class EngineFeeder<H extends Enum<H>> {
    @FunctionalInterface
    protected interface SearchEngineFactory {
        AbstractSearcher create(Predictor predictor, SearchObserver observer);
    }

    private final Class<H> heuristicType;
    private final Map<String, SearchEngineFactory> engines = new LinkedHashMap<>();

    protected EngineFeeder(Class<H> heuristicType) {
        this.heuristicType = Objects.requireNonNull(heuristicType);
        registerEngine("astar", (predictor, observer) -> new BestFirstSearcher(
                getFrontier(EvaluationType.FULL), predictor, observer));
        registerEngine("ida", IdaStarSearcher::new);
        registerEngine("dijkstra", (predictor, observer) -> new BestFirstSearcher(
                getFrontier(EvaluationType.PATH_COST), predictor, observer));
    }

    protected final void registerEngine(String name, SearchEngineFactory factory) {
        String normalized = name.toLowerCase(Locale.ROOT);
        if (engines.putIfAbsent(normalized, Objects.requireNonNull(factory)) != null)
            throw new IllegalArgumentException("Duplicate search engine: " + name);
    }

    public abstract ArrayList<Problem> getProblems(ArrayList<String> problemLines);
    public abstract Frontier getFrontier(EvaluationType type);
    public abstract Predictor getPredictor(H type);

    public final Predictor getPredictor(String name) {
        try {
            return Objects.requireNonNull(getPredictor(Enum.valueOf(heuristicType,
                    name.trim().toUpperCase(Locale.ROOT))), "predictor");
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unsupported heuristic: " + name
                    + "; supported: " + heuristicNames(), exception);
        }
    }

    public final List<String> heuristicNames() {
        return Arrays.stream(heuristicType.getEnumConstants()).map(Enum::name).toList();
    }

    public final List<String> engineNames() { return List.copyOf(engines.keySet()); }

    public final String validateEngine(String name) {
        String normalized = Objects.requireNonNull(name, "engine").toLowerCase(Locale.ROOT);
        if (!engines.containsKey(normalized)) throw new IllegalArgumentException(
                "Unknown search engine: " + name + "; supported: " + engineNames());
        return normalized;
    }

    public final AbstractSearcher getSearcher(String engine, H type) {
        return getSearcher(engine, getPredictor(type), null);
    }

    public final AbstractSearcher getSearcher(String engine, H type, SearchObserver observer) {
        return getSearcher(engine, getPredictor(type), observer);
    }

    public final AbstractSearcher getSearcher(String engine, String heuristic) {
        validateEngine(engine);
        return getSearcher(engine, getPredictor(heuristic), null);
    }

    public final AbstractSearcher getSearcher(String engine, Predictor predictor) {
        return getSearcher(engine, predictor, null);
    }

    /** Ordinary execution and recorded exports use exactly the same factory. */
    public final AbstractSearcher getSearcher(String engine, Predictor predictor, SearchObserver observer) {
        return engines.get(validateEngine(engine)).create(Objects.requireNonNull(predictor), observer);
    }
}
