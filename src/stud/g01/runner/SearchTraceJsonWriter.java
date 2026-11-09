package stud.g01.runner;

import java.io.IOException;
import java.io.Writer;
import java.util.Arrays;
import java.util.Locale;

/** Schema v3 projection of recorded search observations. */
final class SearchTraceJsonWriter {
    private SearchTraceJsonWriter() {}

    static void write(Writer out, SearchTrace trace) throws IOException {
        out.write("{\"complete\":true,\"nodes\":[");
        for (SearchTrace.Entry node : trace.entries()) {
            if (node.id > 0) out.write(',');
            String h =
                    node.quality
                                    != core.solver.algorithm.searcher.SearchObserver
                                            .HeuristicQuality.UNEVALUATED
                            ? Integer.toString(node.h)
                            : "null";
            String f =
                    node.quality
                                    != core.solver.algorithm.searcher.SearchObserver
                                            .HeuristicQuality.UNEVALUATED
                            ? Integer.toString(node.g + node.h)
                            : "null";
            out.write(
                    "{\"id\":"
                            + node.id
                            + ",\"parent\":"
                            + (node.parent < 0 ? "null" : node.parent)
                            + ",\"board\":"
                            + Arrays.toString(node.board)
                            + ",\"g\":"
                            + node.g
                            + ",\"h\":"
                            + h
                            + ",\"f\":"
                            + f
                            + ",\"status\":"
                            + quote(node.status));
            out.write(
                    ",\"heuristicQuality\":"
                            + quote(
                                    switch (node.quality) {
                                        case EXACT -> "exact";
                                        case LOWER_BOUND -> "lower-bound";
                                        case UNEVALUATED -> "unevaluated";
                                    }));
            if (node.reference != null) out.write(",\"reference\":" + node.reference);
            out.write('}');
        }
        out.write("],\"frames\":[");
        boolean first = true;
        for (SearchTrace.Frame frame : trace.frames()) {
            if (!first) out.write(',');
            first = false;
            out.write(
                    "{\"current\":"
                            + frame.current
                            + ",\"children\":"
                            + frame.children
                            + ",\"found\":"
                            + frame.found
                            + ",\"iteration\":"
                            + frame.iteration);
            if (frame.bound >= 0) out.write(",\"bound\":" + frame.bound);
            out.write('}');
        }
        out.write("],\"solution\":" + trace.solutionIds() + "}");
    }

    static String quote(String value) {
        StringBuilder result = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            if (c == '\\' || c == '"') result.append('\\').append(c);
            else if (c < 32 || c == '<' || c == '\u2028' || c == '\u2029') {
                result.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
            } else result.append(c);
        }
        return result.append('"').toString();
    }
}
