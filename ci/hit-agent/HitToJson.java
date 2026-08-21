import java.io.BufferedReader;
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

public final class HitToJson {

  private static final String SOURCE_ROOT = "src/main/java";

  public static void main(String[] args) throws Exception {
    String test = Args.required(args, "--test");
    Path out = Path.of(Args.required(args, "--out"));
    String host = Args.optional(args, "--host", "localhost");
    int port = Integer.parseInt(Args.optional(args, "--port", "6301"));
    boolean reset = Boolean.parseBoolean(Args.optional(args, "--reset", "true"));

    Map<String, ClassDump> dumped = dump(host, port, reset);
    Map<String, FileHits> byPath = new LinkedHashMap<>();

    for (ClassDump cls : dumped.values()) {
      if (cls.sourceFile == null) {
        continue;
      }
      int slash = cls.internalName.lastIndexOf('/');
      String pkgPath = slash < 0 ? "" : cls.internalName.substring(0, slash);
      String path = SOURCE_ROOT + "/" + pkgPath + "/" + cls.sourceFile;
      FileHits file = byPath.computeIfAbsent(path, FileHits::new);
      for (Map.Entry<Integer, Long> e : cls.lines.entrySet()) {
        long hits = e.getValue();
        if (hits <= 0) {
          continue;
        }
        file.lines.merge(e.getKey(), hits, Long::sum);
      }
    }

    List<Object> files = new ArrayList<>();
    for (FileHits file : byPath.values()) {
      if (file.lines.isEmpty()) {
        continue;
      }
      List<Object> lines = new ArrayList<>();
      for (Map.Entry<Integer, Long> e : file.lines.entrySet()) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("nr", e.getKey());
        row.put("hits", e.getValue());
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

  private static final class ClassDump {
    final String internalName;
    String sourceFile;
    final Map<Integer, Long> lines = new LinkedHashMap<>();
    final Map<String, Long> methods = new LinkedHashMap<>();

    ClassDump(String internalName) {
      this.internalName = internalName;
    }
  }

  private static final class FileHits {
    final String path;
    final Map<Integer, Long> lines = new TreeMap<>();

    FileHits(String path) {
      this.path = path;
    }
  }
}