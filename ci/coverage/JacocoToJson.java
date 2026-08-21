import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.analysis.IClassCoverage;
import org.jacoco.core.analysis.ICounter;
import org.jacoco.core.analysis.ILine;
import org.jacoco.core.analysis.IMethodCoverage;
import org.jacoco.core.tools.ExecDumpClient;
import org.jacoco.core.tools.ExecFileLoader;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class JacocoToJson {

  private static final String SOURCE_ROOT = "src/main/java";

  public static void main(String[] args) throws Exception {
    String test = Args.required(args, "--test");
    Path out = Path.of(Args.required(args, "--out"));
    File classesDir = new File(Args.required(args, "--classes"));
    String host = Args.optional(args, "--host", "localhost");
    int port = Integer.parseInt(Args.optional(args, "--port", "6300"));
    boolean reset = Boolean.parseBoolean(Args.optional(args, "--reset", "true"));

    ExecDumpClient client = new ExecDumpClient();
    client.setDump(true);
    client.setReset(reset);
    ExecFileLoader loader = client.dump(host, port);

    CoverageBuilder builder = new CoverageBuilder();
    Analyzer analyzer = new Analyzer(loader.getExecutionDataStore(), builder);
    analyzer.analyzeAll(classesDir);

    Map<String, List<String>> sourceCache = new LinkedHashMap<>();
    Map<String, FileCov> byPath = new LinkedHashMap<>();

    for (IClassCoverage cls : builder.getClasses()) {
      String sourceFile = cls.getSourceFileName();
      if (sourceFile == null) {
        continue;
      }

      String path = SOURCE_ROOT + "/" + cls.getPackageName().replace('.', '/') + "/" + sourceFile;
      FileCov file = byPath.computeIfAbsent(path, FileCov::new);
      List<String> sourceLines = sourceCache.computeIfAbsent(path, JacocoToJson::readSource);

      if (cls.getFirstLine() != -1) {
        for (int nr = cls.getFirstLine(); nr <= cls.getLastLine(); nr++) {
          ILine line = cls.getLine(nr);
          int coveredIns = line.getInstructionCounter().getCoveredCount();
          int missedIns = line.getInstructionCounter().getMissedCount();
          if (coveredIns == 0 && missedIns == 0) {
            continue;
          }

          int endCol = columnEnd(sourceLines, nr);
          String sid = String.valueOf(file.s.size());
          file.statementMap.put(sid, span(nr, 0, nr, endCol));
          file.s.put(sid, coveredIns);

          ICounter branches = line.getBranchCounter();
          if (branches.getTotalCount() > 0) {
            String bid = String.valueOf(file.b.size());
            Map<String, Object> branch = new LinkedHashMap<>();
            branch.put("loc", span(nr, 0, nr, endCol));
            branch.put("type", "cond-expr");
            List<Object> locations = new ArrayList<>();
            locations.add(pos(nr, 0));
            locations.add(pos(nr, endCol));
            branch.put("locations", locations);
            branch.put("line", nr);
            file.branchMap.put(bid, branch);

            List<Integer> hits = new ArrayList<>();
            hits.add(branches.getCoveredCount());
            hits.add(branches.getMissedCount());
            file.b.put(bid, hits);
          }
        }
      }

      for (IMethodCoverage method : cls.getMethods()) {
        int startLine = method.getFirstLine() < 1 ? 1 : method.getFirstLine();
        int endLine = method.getLastLine() < startLine ? startLine : method.getLastLine();
        int endCol = columnEnd(sourceLines, endLine);
        String fid = String.valueOf(file.f.size());

        Map<String, Object> fn = new LinkedHashMap<>();
        fn.put("name", method.getName());
        fn.put("decl", span(startLine, 0, startLine, Math.min(endCol, 1)));
        fn.put("loc", span(startLine, 0, endLine, endCol));
        fn.put("line", startLine);
        file.fnMap.put(fid, fn);
        file.f.put(fid, method.getInstructionCounter().getCoveredCount());
      }
    }

    int lineCovered = 0;
    int lineMissed = 0;
    int branchCovered = 0;
    int branchMissed = 0;

    Map<String, Object> coverage = new LinkedHashMap<>();
    for (FileCov file : byPath.values()) {
      if (!file.wasHit()) {
        continue;
      }

      Map<String, Object> rec = new LinkedHashMap<>();
      rec.put("path", file.path);
      rec.put("statementMap", file.statementMap);
      rec.put("fnMap", file.fnMap);
      rec.put("branchMap", file.branchMap);
      rec.put("s", file.s);
      rec.put("f", file.f);
      rec.put("b", file.b);
      coverage.put(file.path, rec);

      for (Object v : file.s.values()) {
        if (((Number) v).intValue() > 0) {
          lineCovered++;
        } else {
          lineMissed++;
        }
      }
      for (Object v : file.b.values()) {
        @SuppressWarnings("unchecked")
        List<Integer> hits = (List<Integer>) v;
        branchCovered += hits.get(0);
        branchMissed += hits.get(1);
      }
    }

    Map<String, Object> lineSummary = new LinkedHashMap<>();
    lineSummary.put("covered", lineCovered);
    lineSummary.put("missed", lineMissed);
    lineSummary.put("total", lineCovered + lineMissed);

    Map<String, Object> branchSummary = new LinkedHashMap<>();
    branchSummary.put("covered", branchCovered);
    branchSummary.put("missed", branchMissed);
    branchSummary.put("total", branchCovered + branchMissed);

    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("line", lineSummary);
    summary.put("branch", branchSummary);

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("test", test);
    payload.put("type", "backend");
    payload.put("summary", summary);
    payload.put("coverage", coverage);

    Files.createDirectories(out.getParent());
    Files.writeString(out, Json.stringify(payload) + "\n", StandardCharsets.UTF_8);
  }

  private static List<String> readSource(String path) {
    try {
      Path file = Path.of(path);
      if (Files.exists(file)) {
        return Files.readAllLines(file, StandardCharsets.UTF_8);
      }
    } catch (Exception ignored) {
      // use empty source; columns stay 0
    }
    return List.of();
  }

  private static int columnEnd(List<String> lines, int lineNr) {
    if (lineNr < 1 || lineNr > lines.size()) {
      return 0;
    }
    return lines.get(lineNr - 1).length();
  }

  private static Map<String, Object> pos(int line, int column) {
    Map<String, Object> point = new LinkedHashMap<>();
    point.put("line", line);
    point.put("column", column);
    return point;
  }

  private static Map<String, Object> span(int startLine, int startCol, int endLine, int endCol) {
    Map<String, Object> loc = new LinkedHashMap<>();
    loc.put("start", pos(startLine, startCol));
    loc.put("end", pos(endLine, endCol));
    return loc;
  }

  private static final class FileCov {
    final String path;
    final Map<String, Object> statementMap = new LinkedHashMap<>();
    final Map<String, Object> fnMap = new LinkedHashMap<>();
    final Map<String, Object> branchMap = new LinkedHashMap<>();
    final Map<String, Object> s = new LinkedHashMap<>();
    final Map<String, Object> f = new LinkedHashMap<>();
    final Map<String, Object> b = new LinkedHashMap<>();

    FileCov(String path) {
      this.path = path;
    }

    boolean wasHit() {
      for (Object v : s.values()) {
        if (v instanceof Number && ((Number) v).intValue() > 0) {
          return true;
        }
      }
      return false;
    }
  }
}
