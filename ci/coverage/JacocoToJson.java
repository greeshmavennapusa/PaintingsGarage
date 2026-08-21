import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.analysis.IClassCoverage;
import org.jacoco.core.analysis.ICounter;
import org.jacoco.core.analysis.ILine;
import org.jacoco.core.tools.ExecDumpClient;
import org.jacoco.core.tools.ExecFileLoader;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
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
    int hitPort = Integer.parseInt(Args.optional(args, "--hit-port", "6301"));
    int jacocoPort = Integer.parseInt(Args.optional(args, "--jacoco-port", "6300"));
    boolean reset = Boolean.parseBoolean(Args.optional(args, "--reset", "true"));

    Map<String, Map<Integer, LineRow>> byPath = new LinkedHashMap<>();

    addHits(byPath, dumpHits(host, hitPort, reset));
    addJacoco(byPath, classesDir, host, jacocoPort, reset);

    List<Object> files = new ArrayList<>();
    for (Map.Entry<String, Map<Integer, LineRow>> file : byPath.entrySet()) {
      List<Object> lines = new ArrayList<>();
      for (Map.Entry<Integer, LineRow> e : file.getValue().entrySet()) {
        LineRow row = e.getValue();
        if (row.hits <= 0 && row.ci <= 0) {
          continue;
        }
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("nr", e.getKey());
        rec.put("hits", row.hits);
        rec.put("ci", row.ci);
        rec.put("mi", row.mi);
        rec.put("cb", row.cb);
        rec.put("mb", row.mb);
        lines.add(rec);
      }
      if (lines.isEmpty()) {
        continue;
      }
      Map<String, Object> rec = new LinkedHashMap<>();
      rec.put("path", file.getKey());
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

  private static void addHits(Map<String, Map<Integer, LineRow>> byPath, Map<String, ClassDump> dumped) {
    for (ClassDump cls : dumped.values()) {
      if (cls.sourceFile == null) {
        continue;
      }
      int slash = cls.internalName.lastIndexOf('/');
      String pkgPath = slash < 0 ? "" : cls.internalName.substring(0, slash);
      String path = SOURCE_ROOT + "/" + pkgPath + "/" + cls.sourceFile;
      Map<Integer, LineRow> lines = byPath.computeIfAbsent(path, k -> new TreeMap<>());
      for (Map.Entry<Integer, Long> e : cls.lines.entrySet()) {
        if (e.getValue() <= 0) {
          continue;
        }
        lines.computeIfAbsent(e.getKey(), k -> new LineRow()).hits += e.getValue();
      }
    }
  }

  private static void addJacoco(
      Map<String, Map<Integer, LineRow>> byPath,
      File classesDir,
      String host,
      int port,
      boolean reset
  ) throws Exception {
    ExecDumpClient client = new ExecDumpClient();
    client.setDump(true);
    client.setReset(reset);
    ExecFileLoader loader = client.dump(host, port);

    CoverageBuilder builder = new CoverageBuilder();
    Analyzer analyzer = new Analyzer(loader.getExecutionDataStore(), builder);
    analyzer.analyzeAll(classesDir);

    for (IClassCoverage cls : builder.getClasses()) {
      String sourceFile = cls.getSourceFileName();
      if (sourceFile == null || cls.getFirstLine() == -1) {
        continue;
      }
      String path = SOURCE_ROOT + "/" + cls.getPackageName().replace('.', '/') + "/" + sourceFile;
      Map<Integer, LineRow> lines = byPath.computeIfAbsent(path, k -> new TreeMap<>());
      for (int nr = cls.getFirstLine(); nr <= cls.getLastLine(); nr++) {
        ILine line = cls.getLine(nr);
        int ci = line.getInstructionCounter().getCoveredCount();
        int mi = line.getInstructionCounter().getMissedCount();
        if (ci == 0 && mi == 0) {
          continue;
        }
        ICounter branches = line.getBranchCounter();
        LineRow row = lines.computeIfAbsent(nr, k -> new LineRow());
        row.ci += ci;
        row.mi += mi;
        row.cb += branches.getCoveredCount();
        row.mb += branches.getMissedCount();
      }
    }
  }

  private static Map<String, ClassDump> dumpHits(String host, int port, boolean reset) throws Exception {
    Map<String, ClassDump> result = new LinkedHashMap<>();
    try (Socket socket = new Socket(host, port)) {
      PrintWriter writer = new PrintWriter(
          new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
      writer.println("DUMP reset=" + reset);
      BufferedReader reader = new BufferedReader(
          new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
      ClassDump current = null;
      String line;
      while ((line = reader.readLine()) != null) {
        if ("END".equals(line)) {
          break;
        }
        if ("V1".equals(line) || line.isBlank()) {
          continue;
        }
        if (line.startsWith("C ")) {
          current = new ClassDump(line.substring(2).trim());
          result.put(current.internalName, current);
        } else if (current != null && line.startsWith("S ")) {
          current.sourceFile = line.substring(2).trim();
        } else if (current != null && line.startsWith("L ")) {
          String[] p = line.substring(2).trim().split(" ");
          current.lines.put(Integer.parseInt(p[0]), Long.parseLong(p[1]));
        }
      }
    }
    return result;
  }

  private static final class LineRow {
    long hits;
    int ci;
    int mi;
    int cb;
    int mb;
  }

  private static final class ClassDump {
    final String internalName;
    String sourceFile;
    final Map<Integer, Long> lines = new LinkedHashMap<>();

    ClassDump(String internalName) {
      this.internalName = internalName;
    }
  }
}
