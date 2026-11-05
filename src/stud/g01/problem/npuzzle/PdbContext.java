package stud.g01.problem.npuzzle;

/** PDB 的分组估值及其增量回退数据。 */
abstract class PdbContext extends PuzzleSearchContext {
    int directHeuristic, symmetricHeuristic;
    boolean symmetric;

    PdbContext(PuzzleBoard initial, byte[] goal) {
        super(initial, goal);
    }

    @Override
    final int heuristic() {
        return symmetric ? Math.max(directHeuristic, symmetricHeuristic) : directHeuristic;
    }

    static final class Move extends PuzzleSearchContext.Move {
        int group, symmetricGroup;
        int oldIndex, index, oldSymmetricIndex, symmetricIndex;
        int oldValue, value, oldSymmetricValue, symmetricValue;
        int oldDirect, direct, oldSymmetric, symmetric, limit;
    }

    @Override
    final Move newMove() {
        return new Move();
    }

    @Override
    final int prepareHeuristic(PuzzleSearchContext.Move candidate, int limit) {
        Move move = (Move) candidate;
        move.oldDirect = directHeuristic;
        move.oldSymmetric = symmetricHeuristic;
        move.limit = limit;
        prepareValues(move);
        return symmetric ? Math.max(move.direct, move.symmetric) : move.direct;
    }

    abstract void prepareValues(Move move);

    abstract void updateValues(Move move, boolean undo);

    @Override
    final void updateHeuristic(PuzzleSearchContext.Move candidate, boolean undo) {
        Move move = (Move) candidate;
        updateValues(move, undo);
        directHeuristic = undo ? move.oldDirect : move.direct;
        symmetricHeuristic = undo ? move.oldSymmetric : move.symmetric;
    }
}
