import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class HitToJson {

  private static final String SOURCE_ROOT = "src/main/java";

  public static void main(String[] args) throws Exception {
    String test = Args.required(args, "--test");
    Path out = Path.of(Args.required(args, "--out"));
    String host = Args.optional(args, "--host", "localhost");
    int port = Integer.parseInt(Args.optional(args, "--port", "6301"));
    boolean reset = Boolean.parseBoolean(Args.optional(args, "--reset", "true"));

    Map<String, ClassDump> dumped = dump(host, port, reset);
    Map<String, List<String>> sourceCache = new LinkedHashMap<>();
    Map<String, FileCov> byPath = new LinkedHashMap<>();
    int lineCovered = 0;
    int lineMissed = 0;

    for (ClassDump cls : dumped.values()) {
      if (cls.sourceFile == null) {
        continue;
      }
      int slash = cls.internalName.lastIndexOf('/');
      String pkgPath = slash < 0 ? "" : cls.internalName.substring(0, slash);
      String path = SOURCE_ROOT + "/" + pkgPath + "/" + cls.sourceFile;
      FileCov file = byPath.computeIfAbsent(path, FileCov::new);
      List<String> sourceLines = sourceCache.computeIfAbsent(path, HitToJson::readSource);

      for (Map.Entry<Integer, Long> e : cls.lines.entrySet()) {
        int nr = e.getKey();
        long hits = e.getValue();
        String sid = String.valueOf(file.s.size());
        int endCol = columnEnd(sourceLines, nr);
        file.statementMap.put(sid, span(nr, 0, nr, endCol));
        file.s.put(sid, hits);
        if (hits > 0) {
          lineCovered++;
        } else {
          lineMissed++;
        }
      }

      for (Map.Entry<String, Long> e : cls.methods.entrySet()) {
        String fid = String.valueOf(file.f.size());
        Map<String, Object> fn = new LinkedHashMap<>();
        fn.put("name", e.getKey());
        fn.put("decl", span(1, 0, 1, 1));
        fn.put("loc", span(1, 0, 1, 1));
        fn.put("line", 1);
        file.fnMap.put(fid, fn);
        file.f.put(fid, e.getValue());
      }
    }

    Map<String, Object> coverage = new LinkedHashMap<>();
    for (FileCov file : byPath.values()) {
      Map<String, Object> rec = new LinkedHashMap<>();
      rec.put("path", file.path);
      rec.put("statementMap", file.statementMap);
      rec.put("fnMap", file.fnMap);
      rec.put("branchMap", Map.of());
      rec.put("s", file.s);
      rec.put("f", file.f);
      rec.put("b", Map.of());
      coverage.put(file.path, rec);
    }

    Map<String, Object> lineSummary = new LinkedHashMap<>();
    lineSummary.put("covered", lineCovered);
    lineSummary.put("missed", lineMissed);
    lineSummary.put("total", lineCovered + lineMissed);

    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("line", lineSummary);
    summary.put("branch", Map.of("covered", 0, "missed", 0, "total", 0));

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("test", test);
    payload.put("type", "backend");
    payload.put("summary", summary);
    payload.put("coverage", coverage);

    Files.createDirectories(out.getParent());
    Files.writeString(out, Json.stringify(payload) + "\n", StandardCharsets.UTF_8);
  }

  private static Map<String, ClassDump> dump(String host, int port, boolean reset) throws Exception {
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
        if (line.equals("END")) {
          break;
        }
        if (line.equals("V1") || line.isBlank()) {
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
        } else if (current != null && line.startsWith("M ")) {
          String[] p = line.substring(2).trim().split(" ");
          current.methods.put(p[0], Long.parseLong(p[1]));
        }
      }
    }
    return result;
  }

  private static List<String> readSource(String path) {
    try {
      Path file = Path.of(path);
      if (Files.exists(file)) {
        return Files.readAllLines(file, StandardCharsets.UTF_8);
      }
    } catch (Exception ignored) {
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

  private static final class ClassDump {
    final String internalName;
    String sourceFile;
    final Map<Integer, Long> lines = new LinkedHashMap<>();
    final Map<String, Long> methods = new LinkedHashMap<>();

    ClassDump(String internalName) {
      this.internalName = internalName;
    }
  }

  private static final class FileCov {
    final String path;
    final Map<String, Object> statementMap = new LinkedHashMap<>();
    final Map<String, Object> fnMap = new LinkedHashMap<>();
    final Map<String, Object> s = new LinkedHashMap<>();
    final Map<String, Object> f = new LinkedHashMap<>();

    FileCov(String path) {
      this.path = path;
    }
  }
}