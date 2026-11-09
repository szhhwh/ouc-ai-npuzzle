package stud.g01.runner;

import java.io.IOException;
import java.io.Writer;
import java.util.Arrays;
import java.util.Locale;

/** 搜索轨迹 JSON 格式输出工具类，符合第三版输出协议标准规范。 负责将搜索轨迹对象中的结点数据与访问展开帧序列化为规范的 JSON 文本流。 */
final class SearchTraceJsonWriter {

    /** 私有构造函数，防止工具类被实例化。 */
    private SearchTraceJsonWriter() {}

    /**
     * 将搜索轨迹数据完整序列化并写入到字符输出流中。
     *
     * @param out 目标字符输出流写入器
     * @param trace 待输出的搜索轨迹记录对象
     * @throws IOException 当发生输入输出写入异常时抛出
     */
    static void write(Writer out, SearchTrace trace) throws IOException {
        out.write("{\"complete\":true,\"nodes\":[");
        for (SearchTrace.Entry node : trace.entries()) {
            if (node.id > 0) out.write(',');
            // 未评估的启发值输出 null
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

    /**
     * 将普通字符串转换为符合 JSON 格式规范的转义字符串，并使用双引号包裹。
     *
     * @param value 原始文本字符串
     * @return 符合 JSON 转义规则且带有双引号的字符串
     */
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
