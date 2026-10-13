import org.jetbrains.org.objectweb.asm.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;

/**
 * Mini plugin verifier: lists platform classes/members our plugin uses, the jars they come from,
 * and flags Internal / Experimental / ScheduledForRemoval / Deprecated APIs and missing members.
 * Usage: java -cp asm.jar:. ApiCheck <ourClassesDir> <classpath> <bootJarNames.txt>
 */
public class ApiCheck {
  static Map<String, String> classToJar = new HashMap<>();
  static Map<String, byte[]> cache = new HashMap<>();
  static Map<String, JarFile> jars = new HashMap<>();
  static Set<String> ours = new HashSet<>();
  static Set<String> problems = new TreeSet<>();
  static Map<String, Set<String>> jarUse = new TreeMap<>();

  public static void main(String[] a) throws Exception {
    Path ourDir = Path.of(a[0]);
    for (String j : a[1].split(":")) {
      if (j.isBlank() || !Files.exists(Path.of(j))) continue;
      JarFile jf = new JarFile(j);
      jars.put(j, jf);
      for (Enumeration<JarEntry> en = jf.entries(); en.hasMoreElements(); ) {
        String n = en.nextElement().getName();
        if (n.endsWith(".class")) classToJar.putIfAbsent(n.substring(0, n.length() - 6), j);
      }
    }
    Set<String> boot = new HashSet<>(Files.readAllLines(Path.of(a[2])));
    List<Path> classes = new ArrayList<>();
    try (var s = Files.walk(ourDir)) { s.filter(p -> p.toString().endsWith(".class")).forEach(classes::add); }
    for (Path p : classes) ours.add(ourDir.relativize(p).toString().replace(File.separatorChar, '/').replaceAll("\\.class$", ""));

    for (Path p : classes) {
      ClassReader cr = new ClassReader(Files.readAllBytes(p));
      String me = cr.getClassName();
      checkType(me, cr.getSuperName(), "extends");
      for (String i : cr.getInterfaces()) checkType(me, i, "implements");
      cr.accept(new ClassVisitor(Opcodes.ASM9) {
        @Override
        public MethodVisitor visitMethod(int acc, String name, String desc, String sig, String[] ex) {
          // overriding something internal?
          if (!name.startsWith("<") && (acc & Opcodes.ACC_STATIC) == 0 && (acc & Opcodes.ACC_SYNTHETIC) == 0) {
            String[] sup = new String[]{cr.getSuperName()};
            for (String s : concat(sup, cr.getInterfaces())) {
              MemberInfo mi = findMember(s, name, desc, true);
              if (mi != null && (mi.flags.contains("Internal") || mi.flags.contains("ScheduledForRemoval")))
                problems.add("OVERRIDES " + mi.flags + " " + mi.owner + "." + name + desc + "  (in " + me + ")");
            }
          }
          return new MethodVisitor(Opcodes.ASM9) {
            @Override public void visitMethodInsn(int op, String owner, String n, String d, boolean itf) { checkMember(me, owner, n, d, true); }
            @Override public void visitFieldInsn(int op, String owner, String n, String d) { checkMember(me, owner, n, d, false); }
            @Override public void visitTypeInsn(int op, String t) { checkType(me, t, "uses"); }
            @Override public void visitLdcInsn(Object v) { if (v instanceof Type t && t.getSort() == Type.OBJECT) checkType(me, t.getInternalName(), "ldc"); }
            @Override public void visitInvokeDynamicInsn(String n, String d, Handle bsm, Object... args) {
              for (Object o : args) if (o instanceof Handle h) checkMember(me, h.getOwner(), h.getName(), h.getDesc(), true);
            }
          };
        }
      }, 0);
    }
    System.out.println("== jars used (boot classpath = loaded for every plugin):");
    for (var e : jarUse.entrySet()) {
      String name = Path.of(e.getKey()).getFileName().toString();
      String where = boot.contains(name) ? "boot" : e.getKey().contains("vcs-git") ? "Git4Idea plugin" : "NOT BOOT";
      System.out.println("  " + name + " [" + where + "]: " + e.getValue().size() + " classes"
          + (where.equals("NOT BOOT") ? " " + e.getValue() : ""));
    }
    System.out.println("== problems:");
    if (problems.isEmpty()) System.out.println("  none");
    problems.forEach(x -> System.out.println("  " + x));
  }

  static String[] concat(String[] a, String[] b) {
    String[] r = Arrays.copyOf(a, a.length + b.length);
    System.arraycopy(b, 0, r, a.length, b.length);
    return r;
  }

  static boolean platform(String cls) {
    return cls != null && !ours.contains(cls) && !cls.startsWith("java/") && !cls.startsWith("javax/") && !cls.startsWith("[")
        && !cls.startsWith("jdk/") && !cls.startsWith("sun/");
  }

  static void checkType(String from, String cls, String how) {
    if (!platform(cls)) return;
    ClassInfo ci = classInfo(cls);
    if (ci == null) { problems.add("MISSING class " + cls + " (" + how + " in " + from + ")"); return; }
    jarUse.computeIfAbsent(classToJar.get(cls), k -> new TreeSet<>()).add(cls);
    Set<String> f = new TreeSet<>(ci.flags);
    boolean inherits = how.equals("extends") || how.equals("implements");
    f.remove("OverrideOnly");            // implementing / overriding is the intended use
    if (!inherits) f.remove("NonExtendable"); // only matters when we extend/implement it
    if (!f.isEmpty()) problems.add("CLASS " + f + " " + cls + " (" + how + " in " + from + ")");
  }

  static void checkMember(String from, String owner, String name, String desc, boolean method) {
    if (owner.startsWith("[")) return;
    if (!platform(owner)) return;
    checkType(from, owner, "member owner");
    MemberInfo mi = findMember(owner, name, desc, method);
    if (mi == null) { problems.add("MISSING member " + owner + "." + name + desc + " (in " + from + ")"); return; }
    if (mi.owner != null && platform(mi.owner)) jarUse.computeIfAbsent(classToJar.get(mi.owner), k -> new TreeSet<>()).add(mi.owner);
    Set<String> f = new TreeSet<>(mi.flags);
    f.remove("OverrideOnly"); // only matters when calling
    f.remove("NonExtendable");
    if (mi.flags.contains("OverrideOnly")) problems.add("CALLS @OverrideOnly " + mi.owner + "." + name + " (in " + from + ")");
    if (!f.isEmpty()) problems.add("MEMBER " + f + " " + mi.owner + "." + name + desc + " (in " + from + ")");
  }

  record MemberInfo(String owner, Set<String> flags) {}

  static class ClassInfo {
    String name, superName; String[] interfaces; Set<String> flags = new TreeSet<>();
    Map<String, Set<String>> members = new HashMap<>();
  }

  static Map<String, ClassInfo> infos = new HashMap<>();

  static ClassInfo classInfo(String cls) {
    if (infos.containsKey(cls)) return infos.get(cls);
    ClassInfo ci = null;
    try {
      byte[] bytes = bytes(cls);
      if (bytes != null) {
        ClassReader cr = new ClassReader(bytes);
        ClassInfo info = new ClassInfo();
        info.name = cls; info.superName = cr.getSuperName(); info.interfaces = cr.getInterfaces();
        cr.accept(new ClassVisitor(Opcodes.ASM9) {
          @Override public AnnotationVisitor visitAnnotation(String d, boolean v) { flag(info.flags, d); return null; }
          @Override public void visit(int ver, int acc, String n, String s, String sup, String[] i) { if ((acc & Opcodes.ACC_DEPRECATED) != 0) info.flags.add("Deprecated"); }
          @Override public MethodVisitor visitMethod(int acc, String n, String d, String s, String[] e) {
            Set<String> fl = new TreeSet<>();
            if ((acc & Opcodes.ACC_DEPRECATED) != 0) fl.add("Deprecated");
            info.members.put("m:" + n + d, fl);
            return new MethodVisitor(Opcodes.ASM9) { @Override public AnnotationVisitor visitAnnotation(String ad, boolean v) { flag(fl, ad); return null; } };
          }
          @Override public FieldVisitor visitField(int acc, String n, String d, String s, Object v) {
            Set<String> fl = new TreeSet<>();
            if ((acc & Opcodes.ACC_DEPRECATED) != 0) fl.add("Deprecated");
            info.members.put("f:" + n, fl);
            return new FieldVisitor(Opcodes.ASM9) { @Override public AnnotationVisitor visitAnnotation(String ad, boolean v) { flag(fl, ad); return null; } };
          }
        }, ClassReader.SKIP_CODE);
        // package-level annotations
        String pkg = cls.contains("/") ? cls.substring(0, cls.lastIndexOf('/')) : "";
        byte[] pi = bytes(pkg + "/package-info");
        if (pi != null) new ClassReader(pi).accept(new ClassVisitor(Opcodes.ASM9) {
          @Override public AnnotationVisitor visitAnnotation(String d, boolean v) { Set<String> s = new TreeSet<>(); flag(s, d); s.forEach(x -> info.flags.add("pkg:" + x)); return null; }
        }, 0);
        ci = info;
      } else if (cls.startsWith("java/") || cls.startsWith("javax/")) {
        ci = null;
      }
    } catch (IOException e) { ci = null; }
    infos.put(cls, ci);
    return ci;
  }

  static void flag(Set<String> s, String desc) {
    if (desc.contains("ApiStatus$Internal")) s.add("Internal");
    else if (desc.contains("ApiStatus$Experimental")) s.add("Experimental");
    else if (desc.contains("ApiStatus$ScheduledForRemoval")) s.add("ScheduledForRemoval");
    else if (desc.contains("ApiStatus$OverrideOnly")) s.add("OverrideOnly");
    else if (desc.contains("ApiStatus$NonExtendable")) s.add("NonExtendable");
    else if (desc.equals("Ljava/lang/Deprecated;")) s.add("Deprecated");
  }

  static byte[] bytes(String cls) throws IOException {
    if (cache.containsKey(cls)) return cache.get(cls);
    byte[] b = null;
    String jar = classToJar.get(cls);
    if (jar != null) {
      JarFile jf = jars.get(jar);
      try (InputStream in = jf.getInputStream(jf.getEntry(cls + ".class"))) { b = in.readAllBytes(); }
    }
    cache.put(cls, b);
    return b;
  }

  /** Finds a member in the class or its supertypes (JDK supertypes are assumed to have it). */
  static MemberInfo findMember(String owner, String name, String desc, boolean method) {
    Deque<String> todo = new ArrayDeque<>(List.of(owner));
    Set<String> seen = new HashSet<>();
    while (!todo.isEmpty()) {
      String c = todo.poll();
      if (c == null || !seen.add(c)) continue;
      if (!platform(c)) {
        if (c.startsWith("java/") || c.startsWith("javax/")) return new MemberInfo(c, Set.of());
        continue;
      }
      ClassInfo ci = classInfo(c);
      if (ci == null) continue;
      Set<String> fl = ci.members.get(method ? "m:" + name + desc : "f:" + name);
      if (fl != null) return new MemberInfo(c, fl);
      if (ci.superName != null) todo.add(ci.superName);
      if (ci.interfaces != null) todo.addAll(List.of(ci.interfaces));
    }
    return null;
  }
}
