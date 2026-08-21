import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.File;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.jar.JarFile;

public final class HitAgent {

  private static final String PREFIX = "eu/sanjin/kurelic/paintingsgarage/";

  public static void premain(String agentArgs, Instrumentation inst) throws Exception {
    int port = 6301;
    if (agentArgs != null) {
      for (String part : agentArgs.split(",")) {
        if (part.startsWith("port=")) {
          port = Integer.parseInt(part.substring("port=".length()));
        }
      }
    }

    File jar = new File(HitAgent.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    File runtime = new File(jar.getParentFile(), "hit-runtime.jar");
    if (runtime.isFile()) {
      inst.appendToBootstrapClassLoaderSearch(new JarFile(runtime));
    }

    HitRuntime.startServer(port);
    inst.addTransformer(new HitTransformer(), false);
  }

  private static final class HitTransformer implements ClassFileTransformer {
    @Override
    public byte[] transform(
        ClassLoader loader,
        String className,
        Class<?> classBeingRedefined,
        ProtectionDomain protectionDomain,
        byte[] classfileBuffer
    ) {
      if (className == null || !className.startsWith(PREFIX)) {
        return null;
      }
      try {
        ClassReader reader = new ClassReader(classfileBuffer);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS) {
          @Override
          protected String getCommonSuperClass(String type1, String type2) {
            return "java/lang/Object";
          }
        };
        reader.accept(new HitClassVisitor(writer, className), ClassReader.EXPAND_FRAMES);
        return writer.toByteArray();
      } catch (Throwable t) {
        t.printStackTrace();
        return null;
      }
    }
  }

  private static final class HitClassVisitor extends ClassVisitor {
    private final String className;

    HitClassVisitor(ClassVisitor cv, String className) {
      super(Opcodes.ASM9, cv);
      this.className = className;
    }

    @Override
    public void visitSource(String source, String debug) {
      HitRuntime.registerClass(className, source);
      super.visitSource(source, debug);
    }

    @Override
    public MethodVisitor visitMethod(
        int access, String name, String descriptor, String signature, String[] exceptions
    ) {
      MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
      if (mv == null) {
        return null;
      }
      if ((access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
        return mv;
      }
      HitRuntime.registerMethod(className, name);
      return new HitMethodVisitor(mv, className, name);
    }
  }

  private static final class HitMethodVisitor extends MethodVisitor {
    private final String className;
    private final String methodName;

    HitMethodVisitor(MethodVisitor mv, String className, String methodName) {
      super(Opcodes.ASM9, mv);
      this.className = className;
      this.methodName = methodName;
    }

    @Override
    public void visitCode() {
      super.visitCode();
      mv.visitLdcInsn(className);
      mv.visitLdcInsn(methodName);
      mv.visitMethodInsn(
          Opcodes.INVOKESTATIC,
          "HitRuntime",
          "method",
          "(Ljava/lang/String;Ljava/lang/String;)V",
          false
      );
    }

    @Override
    public void visitLineNumber(int line, org.objectweb.asm.Label start) {
      HitRuntime.registerLine(className, line);
      super.visitLineNumber(line, start);
      mv.visitLdcInsn(className);
      mv.visitLdcInsn(line);
      mv.visitMethodInsn(
          Opcodes.INVOKESTATIC,
          "HitRuntime",
          "line",
          "(Ljava/lang/String;I)V",
          false
      );
    }
  }
}