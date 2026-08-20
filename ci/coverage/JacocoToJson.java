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

  public static void main(String[] args) throws Exception {
    String test = Args.required(args, "--test");
    Path out = Path.of(Args.required(args, "--out"));
    File classes = new File(Args.required(args, "--classes"));
    String host = Args.optional(args, "--host", "localhost");
    int port = Integer.parseInt(Args.optional(args, "--port", "6300"));
    boolean reset = Boolean.parseBoolean(Args.optional(args, "--reset", "true"));

    ExecDumpClient client = new ExecDumpClient();
    client.setDump(true);
    client.setReset(reset);
    ExecFileLoader loader = client.dump(host, port);

    CoverageBuilder builder = new CoverageBuilder();
    Analyzer analyzer = new Analyzer(loader.getExecutionDataStore(), builder);
    analyzer.analyzeAll(classes);

    Map<String, Object> files = new LinkedHashMap<>();
    int lineCovered = 0;
    int lineMissed = 0;
    int branchCovered = 0;
    int branchMissed = 0;

    for (IClassCoverage cls : builder.getClasses()) {
      if (cls.getName().startsWith("eu/sanjin/kurelic/paintingsgarage/coverage")) {
        continue;
      }
      String source = cls.getSourceFileName();
      if (source == null) {
        continue;
      }
      String path = "src/main/java/" + cls.getPackageName().replace('.', '/') + "/" + source;

      Map<String, Object> s = new LinkedHashMap<>();
      Map<String, Object> l = new LinkedHashMap<>();
      Map<String, Object> b = new LinkedHashMap<>();
      Map<String, Object> f = new LinkedHashMap<>();

      if (cls.getFirstLine() != -1) {
        for (int nr = cls.getFirstLine(); nr <= cls.getLastLine(); nr++) {
          ILine line = cls.getLine(nr);
          int coveredIns = line.getInstructionCounter().getCoveredCount();
          int missedIns = line.getInstructionCounter().getMissedCount();
          if (coveredIns == 0 && missedIns == 0) {
            continue;
          }
          String key = String.valueOf(nr);
          l.put(key, coveredIns);
          s.put(key, coveredIns > 0 ? 1 : 0);

          ICounter br = line.getBranchCounter();
          if (br.getTotalCount() > 0) {
            List<Integer> pair = new ArrayList<>();
            pair.add(br.getCoveredCount());
            pair.add(br.getMissedCount());
            b.put(key, pair);
          }
          if (coveredIns > 0) {
            lineCovered++;
          } else {
            lineMissed++;
          }
          branchCovered += br.getCoveredCount();
          branchMissed += br.getMissedCount();
        }
      }

      int methodIndex = 0;
      for (IMethodCoverage method : cls.getMethods()) {
        f.put(String.valueOf(methodIndex++), method.getInstructionCounter().getCoveredCount() > 0 ? 1 : 0);
      }

      Map<String, Object> file = new LinkedHashMap<>();
      file.put("s", s);
      file.put("l", l);
      file.put("b", b);
      file.put("f", f);

      @SuppressWarnings("unchecked")
      Map<String, Object> existing = (Map<String, Object>) files.get(path);
      if (existing == null) {
        files.put(path, file);
      } else {
        mergeNumericMap(existing, "s", s);
        mergeNumericMap(existing, "l", l);
        mergeBranchMap(existing, b);
        mergeNumericMap(existing, "f", f);
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
    payload.put("coverage", files);

    Files.createDirectories(out.getParent());
    Files.writeString(out, Json.stringify(payload) + "\n", StandardCharsets.UTF_8);
  }

  @SuppressWarnings("unchecked")
  private static void mergeNumericMap(Map<String, Object> existing, String key, Map<String, Object> incoming) {
    Map<String, Object> target = (Map<String, Object>) existing.get(key);
    for (var e : incoming.entrySet()) {
      int next = ((Number) e.getValue()).intValue();
      Object prev = target.get(e.getKey());
      int old = prev == null ? 0 : ((Number) prev).intValue();
      target.put(e.getKey(), Math.max(old, next));
    }
  }

  @SuppressWarnings("unchecked")
  private static void mergeBranchMap(Map<String, Object> existing, Map<String, Object> incoming) {
    Map<String, Object> target = (Map<String, Object>) existing.get("b");
    for (var e : incoming.entrySet()) {
      List<Integer> next = (List<Integer>) e.getValue();
      List<Integer> prev = (List<Integer>) target.get(e.getKey());
      if (prev == null) {
        target.put(e.getKey(), next);
      } else {
        List<Integer> merged = new ArrayList<>();
        merged.add(Math.max(prev.get(0), next.get(0)));
        merged.add(Math.max(prev.get(1), next.get(1)));
        target.put(e.getKey(), merged);
      }
    }
  }
}