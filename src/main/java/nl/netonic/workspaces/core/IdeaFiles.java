package nl.netonic.workspaces.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Prepares the {@code .idea} folder of a ticket so it opens as one PhpStorm project holding all its repos.
 *
 * <ul>
 *   <li>Shared settings (PHP interpreter, code style, run configs, data sources) are copied from main/.idea.</li>
 *   <li>{@code workspace.xml} is never copied: a shared ProjectId would make PhpStorm treat two folders as one project.</li>
 *   <li>{@code .name} sets the tab title, e.g. "klant-x · LOGIN-42".</li>
 *   <li>{@code vcs.xml} maps every repo (including ones attached from main/) as a Git root.</li>
 *   <li>Repos the ticket doesn't touch are attached from main/ as extra content roots.</li>
 * </ul>
 */
public final class IdeaFiles {
  /** Never copied from main/.idea into a ticket. */
  static final Set<String> SKIP = Set.of(
      "workspace.xml", ".name", "vcs.xml", "shelf", "tasks.xml", "usage.statistics.xml",
      "sonarlint", "httpRequests", "claudeCodeTabState.xml", "jsLibraryMappings.xml");

  private IdeaFiles() {}

  public static Path ideaDir(Ticket t) {
    return t.getDir().resolve(".idea");
  }

  /** Creates or refreshes the ticket's .idea. Safe to run every time before opening. */
  public static void prepare(Ticket ticket) throws IOException {
    Path idea = ideaDir(ticket);
    Path mainIdea = ticket.getWorkspace().mainDir().resolve(".idea");
    boolean fresh = !Files.isDirectory(idea);
    Files.createDirectories(idea);

    if (fresh && !ticket.isMain() && Files.isDirectory(mainIdea)) {
      copySharedSettings(mainIdea, idea);
    }

    Path iml = ensureModule(ticket, idea);
    writeName(ticket, idea);
    writeVcsMappings(ticket, idea);
    syncAttachedContentRoots(ticket, iml);
  }

  static void copySharedSettings(Path from, Path to) throws IOException {
    try (Stream<Path> s = Files.list(from)) {
      for (Path p : (Iterable<Path>) s::iterator) {
        String n = p.getFileName().toString();
        if (SKIP.contains(n) || n.endsWith(".iws")) continue;
        copyTree(p, to.resolve(n));
      }
    }
  }

  private static void copyTree(Path src, Path dst) throws IOException {
    if (!Files.isDirectory(src)) {
      Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
      return;
    }
    Files.walkFileTree(src, new SimpleFileVisitor<>() {
      @Override
      public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
        Files.createDirectories(dst.resolve(src.relativize(dir).toString()));
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
        Files.copy(file, dst.resolve(src.relativize(file).toString()), StandardCopyOption.REPLACE_EXISTING);
        return FileVisitResult.CONTINUE;
      }
    });
  }

  /** Makes sure modules.xml points at an .iml whose content root is the ticket folder; returns that .iml. */
  static Path ensureModule(Ticket ticket, Path idea) throws IOException {
    Path modules = idea.resolve("modules.xml");
    if (Files.isRegularFile(modules)) {
      String xml = Files.readString(modules, StandardCharsets.UTF_8);
      Matcher m = Pattern.compile("filepath=\"\\$PROJECT_DIR\\$/\\.idea/([^\"/]+\\.iml)\"").matcher(xml);
      if (m.find()) {
        Path iml = idea.resolve(m.group(1));
        if (Files.isRegularFile(iml)) return iml;
      }
    }
    String imlName = safeFileName(ticket.getWorkspace().getName() + "-" + ticket.getName()) + ".iml";
    Path iml = idea.resolve(imlName);
    if (!Files.isRegularFile(iml)) {
      Files.writeString(iml, """
          <?xml version="1.0" encoding="UTF-8"?>
          <module type="WEB_MODULE" version="4">
            <component name="NewModuleRootManager">
              <content url="file://$MODULE_DIR$" />
              <orderEntry type="inheritedJdk" />
              <orderEntry type="sourceFolder" forTests="false" />
            </component>
          </module>
          """, StandardCharsets.UTF_8);
    }
    Files.writeString(modules, """
        <?xml version="1.0" encoding="UTF-8"?>
        <project version="4">
          <component name="ProjectModuleManager">
            <modules>
              <module fileurl="file://$PROJECT_DIR$/.idea/%1$s" filepath="$PROJECT_DIR$/.idea/%1$s" />
            </modules>
          </component>
        </project>
        """.formatted(imlName), StandardCharsets.UTF_8);
    return iml;
  }

  static void writeName(Ticket ticket, Path idea) throws IOException {
    Files.writeString(idea.resolve(".name"), ticket.displayName(), StandardCharsets.UTF_8);
  }

  static void writeVcsMappings(Ticket ticket, Path idea) throws IOException {
    StringBuilder b = new StringBuilder();
    b.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<project version=\"4\">\n");
    b.append("  <component name=\"VcsDirectoryMappings\">\n");
    for (TicketRepo r : ticket.repos()) {
      if (r.kind() == TicketRepo.Kind.MISSING) continue;
      String dir = r.kind() == TicketRepo.Kind.ATTACHED
          ? "$PROJECT_DIR$/../" + Workspace.MAIN + "/" + r.name()
          : "$PROJECT_DIR$/" + r.name();
      b.append("    <mapping directory=\"").append(xmlAttr(dir)).append("\" vcs=\"Git\" />\n");
    }
    b.append("  </component>\n</project>\n");
    Files.writeString(idea.resolve("vcs.xml"), b.toString(), StandardCharsets.UTF_8);
  }

  /**
   * Adds a {@code <content>} entry for every attached repo and removes entries for repos
   * that are no longer attached. Only entries pointing into this workspace's main/ are touched.
   */
  static void syncAttachedContentRoots(Ticket ticket, Path iml) throws IOException {
    if (ticket.isMain() || !Files.isRegularFile(iml)) return;
    String xml = Files.readString(iml, StandardCharsets.UTF_8);
    String original = xml;
    Path main = ticket.getWorkspace().mainDir().toAbsolutePath().normalize();
    Path moduleDir = ticket.getDir().toAbsolutePath().normalize();

    List<Path> wanted = new ArrayList<>();
    for (TicketRepo r : ticket.repos()) {
      if (r.kind() == TicketRepo.Kind.ATTACHED) wanted.add(r.dir().toAbsolutePath().normalize());
    }

    // <content url="..."/>  or  <content url="..."> <excludeFolder .../> </content>
    Pattern contentTag = Pattern.compile("\\s*<content url=\"([^\"]+)\"\\s*(?:/>|>.*?</content>)", Pattern.DOTALL);
    Matcher m = contentTag.matcher(xml);
    StringBuilder out = new StringBuilder();
    Set<Path> kept = new HashSet<>();
    while (m.find()) {
      Path p = urlToPath(m.group(1), moduleDir);
      boolean managed = p != null && p.startsWith(main) && !p.equals(main);
      boolean keep = !managed || (wanted.contains(p) && kept.add(p));
      m.appendReplacement(out, keep ? Matcher.quoteReplacement(m.group()) : "");
    }
    m.appendTail(out);
    xml = out.toString();

    List<Path> missing = wanted.stream().filter(p -> !kept.contains(p)).toList();
    if (!missing.isEmpty()) {
      int at = xml.indexOf("<component name=\"NewModuleRootManager\"");
      if (at >= 0) {
        int close = xml.indexOf('>', at);
        StringBuilder add = new StringBuilder();
        for (Path p : missing) add.append("\n    <content url=\"").append(xmlAttr(fileUrl(p))).append("\" />");
        xml = xml.substring(0, close + 1) + add + xml.substring(close + 1);
      }
    }
    if (!xml.equals(original)) Files.writeString(iml, xml, StandardCharsets.UTF_8);
  }

  /** file:// URL (absolute or $MODULE_DIR$-relative) to a normalized path; null for anything else. */
  static Path urlToPath(String url, Path moduleDir) {
    if (!url.startsWith("file://")) return null;
    String path = url.substring("file://".length()).replace("$MODULE_DIR$", moduleDir.toString());
    if (path.contains("$")) return null;
    try {
      return Path.of(path).toAbsolutePath().normalize();
    } catch (RuntimeException e) {
      return null;
    }
  }

  static String fileUrl(Path p) {
    return "file://" + p.toAbsolutePath().normalize().toString().replace('\\', '/');
  }

  static String safeFileName(String s) {
    return s.replaceAll("[^A-Za-z0-9._-]", "_");
  }

  private static String xmlAttr(String s) {
    return s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
  }
}
