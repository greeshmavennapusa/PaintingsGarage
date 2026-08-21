import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

public final class HitRuntime {

  private static final ConcurrentHashMap<String, ClassHits> CLASSES = new ConcurrentHashMap<>();

  private HitRuntime() {}

  public static void registerClass(String internalName, String sourceFile) {
    ClassHits hits = CLASSES.computeIfAbsent(internalName, ClassHits::new);
    if (sourceFile != null) {
      hits.sourceFile = sourceFile;
    }
  }

  public static void registerLine(String internalName, int line) {
    classHits(internalName).lines.computeIfAbsent(line, k -> new LongAdder());
  }

  public static void registerMethod(String internalName, String method) {
    classHits(internalName).methods.computeIfAbsent(method, k -> new LongAdder());
  }

  public static void line(String internalName, int line) {
    classHits(internalName).lines.computeIfAbsent(line, k -> new LongAdder()).increment();
  }

  public static void method(String internalName, String method) {
    classHits(internalName).methods.computeIfAbsent(method, k -> new LongAdder()).increment();
  }

  public static void reset() {
    for (ClassHits hits : CLASSES.values()) {
      for (LongAdder adder : hits.lines.values()) {
        adder.reset();
      }
      for (LongAdder adder : hits.methods.values()) {
        adder.reset();
      }
    }
  }

  public static void startServer(int port) {
    Thread thread = new Thread(() -> serve(port), "hit-dump");
    thread.setDaemon(true);
    thread.start();
  }

  private static ClassHits classHits(String internalName) {
    return CLASSES.computeIfAbsent(internalName, ClassHits::new);
  }

  private static void serve(int port) {
    try (ServerSocket server = new ServerSocket(port)) {
      while (true) {
        try (Socket socket = server.accept()) {
          BufferedReader in = new BufferedReader(
              new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
          BufferedWriter out = new BufferedWriter(
              new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
          String command = in.readLine();
          writeDump(out);
          out.flush();
          if (command != null && command.contains("reset=true")) {
            reset();
          }
        } catch (Exception ignored) {
          // next connection
        }
      }
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  private static void writeDump(BufferedWriter out) throws Exception {
    out.write("V1\n");
    for (Map.Entry<String, ClassHits> e : CLASSES.entrySet()) {
      ClassHits hits = e.getValue();
      out.write("C ");
      out.write(e.getKey());
      out.write('\n');
      if (hits.sourceFile != null) {
        out.write("S ");
        out.write(hits.sourceFile);
        out.write('\n');
      }
      for (Map.Entry<Integer, LongAdder> line : hits.lines.entrySet()) {
        out.write("L ");
        out.write(Integer.toString(line.getKey()));
        out.write(' ');
        out.write(Long.toString(line.getValue().sum()));
        out.write('\n');
      }
      for (Map.Entry<String, LongAdder> method : hits.methods.entrySet()) {
        out.write("M ");
        out.write(method.getKey().replace(' ', '_'));
        out.write(' ');
        out.write(Long.toString(method.getValue().sum()));
        out.write('\n');
      }
    }
    out.write("END\n");
  }

  private static final class ClassHits {
    String sourceFile;
    final ConcurrentHashMap<Integer, LongAdder> lines = new ConcurrentHashMap<>();
    final ConcurrentHashMap<String, LongAdder> methods = new ConcurrentHashMap<>();

    ClassHits(String ignored) {}
  }
}