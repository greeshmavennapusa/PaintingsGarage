import java.util.Collection;
import java.util.Map;

final class Json {

  private Json() {}

  static String stringify(Object value) {
    StringBuilder sb = new StringBuilder();
    write(sb, value);
    return sb.toString();
  }

  private static void write(StringBuilder sb, Object value) {
    if (value == null) {
      sb.append("null");
    } else if (value instanceof String s) {
      sb.append('"').append(escape(s)).append('"');
    } else if (value instanceof Number || value instanceof Boolean) {
      sb.append(value);
    } else if (value instanceof Map<?, ?> map) {
      sb.append('{');
      boolean first = true;
      for (var e : map.entrySet()) {
        if (!first) {
          sb.append(',');
        }
        first = false;
        write(sb, String.valueOf(e.getKey()));
        sb.append(':');
        write(sb, e.getValue());
      }
      sb.append('}');
    } else if (value instanceof Collection<?> col) {
      sb.append('[');
      boolean first = true;
      for (Object item : col) {
        if (!first) {
          sb.append(',');
        }
        first = false;
        write(sb, item);
      }
      sb.append(']');
    } else {
      write(sb, String.valueOf(value));
    }
  }

  private static String escape(String s) {
    return s.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}