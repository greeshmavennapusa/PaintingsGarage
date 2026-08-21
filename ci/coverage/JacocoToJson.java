import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.analysis.IClassCoverage;
import org.jacoco.core.analysis.ICounter;
import org.jacoco.core.analysis.ILine;
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
import java.util.TreeMap;

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

    Map<String, FileLines> byPath = new LinkedHashMap<>();

    for (IClassCoverage cls : builder.getClasses()) {
      String sourceFile = cls.getSourceFileName();
      if (sourceFile == null) {
        continue;
      }
      String path = SOURCE_ROOT + "/" + cls.getPackageName().replace('.', '/') + "/" + sourceFile;
      FileLines file = byPath.computeIfAbsent(path, FileLines::new);
      if (cls.getFirstLine() == -1) {
        continue;
      }
      for (int nr = cls.getFirstLine(); nr <= cls.getLastLine(); nr++) {
        ILine line = cls.getLine(nr);
        int ci = line.getInstructionCounter().getCoveredCount();
        int mi = line.getInstructionCounter().getMissedCount();
        if (ci == 0) {
          continue;
        }
        ICounter branches = line.getBranchCounter();
        LineRow row = file.lines.computeIfAbsent(nr, k -> new LineRow());
        row.ci += ci;
        row.mi += mi;
        row.cb += branches.getCoveredCount();
        row.mb += branches.getMissedCount();
      }
    }

    List<Object> files = new ArrayList<>();
    for (FileLines file : byPath.values()) {
      if (file.lines.isEmpty()) {
        continue;
      }
      List<Object> lines = new ArrayList<>();
      for (Map.Entry<Integer, LineRow> e : file.lines.entrySet()) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("nr", e.getKey());
        row.put("ci", e.getValue().ci);
        row.put("mi", e.getValue().mi);
        row.put("cb", e.getValue().cb);
        row.put("mb", e.getValue().mb);
        lines.add(row);
      }
      Map<String, Object> rec = new LinkedHashMap<>();
      rec.put("path", file.path);
      rec.put("lines", lines);
      files.add(rec);
    }

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("test", test);
    payload.put("type", "backend");
    payload.put("files", files);

    Files.createDirectories(out.getParent());
    Files.writeString(out, Json.stringify(payload) + "\n", StandardCharsets.UTF_8);
  }

  private static final class FileLines {
    final String path;
    final Map<Integer, LineRow> lines = new TreeMap<>();

    FileLines(String path) {
      this.path = path;
    }
  }

  private static final class LineRow {
    int ci;
    int mi;
    int cb;
    int mb;
  }
}