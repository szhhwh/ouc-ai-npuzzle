package stud.g01.problem.npuzzle;

/** Independent PDB switches, captured when a searcher or predictor is created. */
public record PdbOptions(boolean incremental, boolean symmetry) {

    /** Both optimizations default to enabled. */
    public static PdbOptions fromSystemProperties() {
        return new PdbOptions(
                readBoolean("npuzzle.pdb.incremental"), readBoolean("npuzzle.pdb.symmetry"));
    }

    private static boolean readBoolean(String name) {
        String value = System.getProperty(name, "true");
        if ("true".equalsIgnoreCase(value)) return true;
        if ("false".equalsIgnoreCase(value)) return false;
        throw new IllegalArgumentException(name + " must be true or false: " + value);
    }
}
