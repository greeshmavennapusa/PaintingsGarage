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
      String source = cls.getSourceFileName();
      if (source == null) {
        continue;
      }
      String path = "src/main/java/" + cls.getPackageName().replace('.', '/') + "/" + source;

      Map<String, Object> s = new LinkedHashMap<>();
      Map<String, Object> f = new LinkedHashMap<>();
      Map<String, Object> b = new LinkedHashMap<>();
      int sId = 0;
      int fId = 0;
      int bId = 0;

      if (cls.getFirstLine() != -1) {
        for (int nr = cls.getFirstLine(); nr <= cls.getLastLine(); nr++) {
          ILine line = cls.getLine(nr);
          int coveredIns = line.getInstructionCounter().getCoveredCount();
          int missedIns = line.getInstructionCounter().getMissedCount();
          if (coveredIns == 0 && missedIns == 0) {
            continue;
          }
          if (coveredIns > 0) {
            s.put(String.valueOf(sId++), 1);
            lineCovered++;
          } else {
            lineMissed++;
          }

          ICounter br = line.getBranchCounter();
          if (br.getCoveredCount() > 0) {
            List<Integer> pair = new ArrayList<>();
            pair.add(br.getCoveredCount());
            pair.add(br.getMissedCount());
            b.put(String.valueOf(bId++), pair);
          }
          branchCovered += br.getCoveredCount();
          branchMissed += br.getMissedCount();
        }
      }

      for (IMethodCoverage method : cls.getMethods()) {
        if (method.getInstructionCounter().getCoveredCount() > 0) {
          f.put(String.valueOf(fId++), 1);
        }
      }

      if (s.isEmpty() && f.isEmpty() && b.isEmpty()) {
        continue;
      }

      Map<String, Object> file = new LinkedHashMap<>();
      if (!s.isEmpty()) {
        file.put("s", s);
      }
      if (!f.isEmpty()) {
        file.put("f", f);
      }
      if (!b.isEmpty()) {
        file.put("b", b);
      }

      @SuppressWarnings("unchecked")
      Map<String, Object> existing = (Map<String, Object>) files.get(path);
      if (existing == null) {
        files.put(path, file);
      } else {
        mergeIdMap(existing, "s", s);
        mergeIdMap(existing, "f", f);
        mergeBranchMap(existing, b);
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
  private static void mergeIdMap(Map<String, Object> existing, String key, Map<String, Object> incoming) {
    if (incoming.isEmpty()) {
      return;
    }
    Map<String, Object> target = (Map<String, Object>) existing.get(key);
    if (target == null) {
      existing.put(key, new LinkedHashMap<>(incoming));
      return;
    }
    int nextId = target.size();
    for (Object value : incoming.values()) {
      target.put(String.valueOf(nextId++), value);
    }
  }

  @SuppressWarnings("unchecked")
  private static void mergeBranchMap(Map<String, Object> existing, Map<String, Object> incoming) {
    if (incoming.isEmpty()) {
      return;
    }
    Map<String, Object> target = (Map<String, Object>) existing.get("b");
    if (target == null) {
      existing.put("b", new LinkedHashMap<>(incoming));
      return;
    }
    int nextId = target.size();
    for (Object value : incoming.values()) {
      target.put(String.valueOf(nextId++), value);
    }
  }
}
