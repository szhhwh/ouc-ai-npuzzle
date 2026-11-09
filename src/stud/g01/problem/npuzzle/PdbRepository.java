package stud.g01.problem.npuzzle;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Owns lazy in-memory PDB tables and their disk location. Use a separate repository to isolate a
 * run's cache lifetime and directory; construction does not read files or build tables.
 */
public final class PdbRepository {
    private final Path directory;
    private final GoalTables<DisjointPdbHeuristic.Tables> product;
    private final GoalTables<RegularPdbHeuristic.Tables> regular;
    private final GoalTables<ComponentPdbContext.Tables> component;

    public PdbRepository(Path directory) {
        this.directory =
                Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        product = new GoalTables<>(goal -> DisjointPdbHeuristic.Tables.build(goal, this));
        regular = new GoalTables<>(goal -> new RegularPdbHeuristic.Tables(goal, this));
        component = new GoalTables<>(goal -> new ComponentPdbContext.Tables(goal, this));
    }

    public Path directory() {
        return directory;
    }

    /**
     * CLI default, captured lazily once; explicit repositories isolate cache location and lifetime.
     */
    public static PdbRepository defaultRepository() {
        return DefaultHolder.INSTANCE;
    }

    private static final class DefaultHolder {
        static final PdbRepository INSTANCE =
                new PdbRepository(
                        Path.of(System.getProperty("npuzzle.pdb.cacheDir", "resources/pdb-cache")));
    }

    DisjointPdbHeuristic.Tables productTables(PuzzleBoard goal) {
        requireSize(goal, 3);
        return product.get(goal);
    }

    RegularPdbHeuristic.Tables regularTables(PuzzleBoard goal) {
        requireSize(goal, 4);
        return regular.get(goal);
    }

    ComponentPdbContext.Tables componentTables(PuzzleBoard goal) {
        requireSize(goal, 4);
        return component.get(goal);
    }

    private static void requireSize(PuzzleBoard goal, int size) {
        Objects.requireNonNull(goal, "goal");
        if (goal.size() != size) {
            throw new UnsupportedOperationException("This PDB strategy requires size " + size);
        }
    }

    private static final class GoalTables<T> {
        private final Map<PuzzleBoard, T> built = new ConcurrentHashMap<>();
        private final Function<PuzzleBoard, T> build;
        private volatile Entry<T> last;

        private record Entry<T>(PuzzleBoard goal, T tables) {}

        GoalTables(Function<PuzzleBoard, T> build) {
            this.build = build;
        }

        T get(PuzzleBoard goal) {
            Entry<T> memo = last;
            if (memo != null && memo.goal == goal) return memo.tables;
            T tables = built.computeIfAbsent(goal, build);
            last = new Entry<>(goal, tables);
            return tables;
        }
    }
}
