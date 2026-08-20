import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class WriteFrontendJson {

  private static final Pattern FETCH_PAGE = Pattern.compile("fetchPage\\(\\s*\"([^\"]*)\"\\s*\\)");
  private static final Pattern FETCH_ROOT = Pattern.compile("fetchRootPage\\s*\\(\\s*\\)");

  public static void main(String[] args) throws Exception {
    String test = Args.required(args, "--test");
    Path sourceRoot = Path.of(Args.optional(args, "--source", "src/test/java"));
    Path out = Path.of(Args.required(args, "--out"));

    Path testFile = findTestFile(sourceRoot, test);
    String source = Files.readString(testFile);

    Map<String, Integer> pages = new LinkedHashMap<>();
    Matcher pageMatcher = FETCH_PAGE.matcher(source);
    while (pageMatcher.find()) {
      String raw = pageMatcher.group(1);
      String path = raw.isBlank() ? "/" : (raw.startsWith("/") ? raw : "/" + raw);
      pages.merge(path, 1, Integer::sum);
    }
    Matcher rootMatcher = FETCH_ROOT.matcher(source);
    while (rootMatcher.find()) {
      pages.merge("/", 1, Integer::sum);
    }

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("test", test);
    payload.put("type", "frontend");
    payload.put("runner", "selenium");
    payload.put("browser", "firefox");
    payload.put("pages", pages);

    Files.createDirectories(out.getParent());
    Files.writeString(out, Json.stringify(payload) + "\n", StandardCharsets.UTF_8);
  }

  private static Path findTestFile(Path sourceRoot, String testName) throws Exception {
    try (Stream<Path> stream = Files.walk(sourceRoot)) {
      return stream
        .filter(p -> p.getFileName().toString().equals(testName + ".java"))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("test source not found: " + testName));
    }
  }
}