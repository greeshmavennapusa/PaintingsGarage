final class Args {

  private Args() {}

  static String required(String[] args, String name) {
    for (int i = 0; i < args.length - 1; i++) {
      if (name.equals(args[i])) {
        return args[i + 1];
      }
    }
    throw new IllegalArgumentException("missing " + name);
  }

  static String optional(String[] args, String name, String defaultValue) {
    for (int i = 0; i < args.length - 1; i++) {
      if (name.equals(args[i])) {
        return args[i + 1];
      }
    }
    return defaultValue;
  }
}