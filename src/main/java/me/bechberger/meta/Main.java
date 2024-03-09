package me.bechberger.meta;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.util.JavalinLogger;
import java.io.*;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import me.bechberger.meta.BytecodeDiffUtils.DiffSourceMode;
import me.bechberger.meta.runtime.InstrumentationHandler;

/** Agent entry */
public class Main {

  private static Instrumentation inst;

  private static int parsePortFromArgs(String args) {
    int port = 7071;
    if (args != null) {
      String[] split = args.split("=");
      if (split.length == 1 && split[0].equals("help")) {
        System.out.println("Usage: -javaagent:meta-runtime.jar=port=...<7071>");
        System.exit(0);
      }
      if (split.length == 2 && split[0].equals("port")) {
        try {
          port = Integer.parseInt(split[1]);
        } catch (NumberFormatException e) {
          System.err.println("Invalid port number: " + split[1]);
        }
      } else if (!args.isEmpty()) {
        System.err.println("Invalid argument: " + args + ", expected 'port=...'");
      }
    }
    return port;
  }

  public static void agentmain(String args, Instrumentation inst) {
    premain(args, inst);
  }

  public static void premain(String agentArgs, Instrumentation inst) {
    try {
      inst.appendToBootstrapClassLoaderSearch(new JarFile(getExtractedJARPath().toFile()));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    Main.inst = inst;
    inst.addTransformer(new ClassTransformer(), true);
    // transform all loaded classes
    triggerRetransformOfAllClasses(inst);
    // start server
    int port = parsePortFromArgs(agentArgs);
    // System.setProperty(org.slf4j.simple.SimpleLogger.DEFAULT_LOG_LEVEL_KEY, "ERROR");
    Thread thread =
        new Thread(
            () -> runServer(port));
    thread.setDaemon(true);
    thread.start();
  }

  private static void triggerRetransformOfAllClasses(Instrumentation inst) {
    for (var clazz : inst.getAllLoadedClasses()) {
      if (clazz.isInterface()) {
        continue;
      }
      try {
        inst.retransformClasses(clazz);
      } catch (UnmodifiableClassException e) {
        System.out.println("Can't modify class " + clazz);
      } catch (Exception e) {
        e.printStackTrace();
      }
    }
  }

  private record Command(List<String> path, Handler handler, String description, String example) {
    Command(String path, Handler handler, String description, String example) {
      this(List.of(path), handler, description, example);
    }
  }

  private static final List<Command> commands =
      List.of(
          new Command(List.of("/", "/help"), Main::help, "Show this help", "/help"),
          new Command(
              "/instrumentators",
              Main::listInstrumentators,
              "List all instrumentators",
              "/instrumentators"),
          new Command(
              "/instrumentators/{pattern}",
              Main::listInstrumentators,
              "List instrumentators matching the given glob pattern",
              "/instrumentators/org.mockito.*"),
          new Command(
              "/diff/instrumentator/{pattern}",
              Main::showInstrumentatorDiffs,
              "Show diffs for instrumentator matching the given glob pattern",
              "/diff/instrumentator/org.mockito.*"),
          new Command(
              "/full-diff/instrumentator/{pattern}",
              Main::showInstrumentatorDiffs,
              "Show diffs with all context for instrumentator matching the given glob pattern",
              "/full-diff/instrumentator/org.mockito.*"),
          new Command("/classes", Main::listClasses, "List all classes", "/classes"),
          new Command(
              "/classes/{pattern}",
              Main::listClasses,
              "List transformed classes matching the given glob pattern",
              "/classes/java.util.*"),
          new Command("/all/classes", Main::listClasses, "List all classes", "/all/classes"),
          new Command(
              "/all/classes/{pattern}",
              Main::listClasses,
              "List all classes matching the given glob pattern",
              "/all/classes/java.util.*"),
          new Command(
              "/diff/class/{pattern}",
              Main::showClassDiffs,
              "Show diffs for transformed classes matching the given glob pattern",
              "/diff/class/java.util.*"),
          new Command(
              "/full-diff/class/{pattern}",
              Main::showClassDiffs,
              "Show diffs with all context for transformed classes matching the given glob pattern",
              "/full-diff/class/java.util.*"),
          new Command(
              "/diff/class-instr/{pattern}/{instr}",
              Main::showClassDiffs,
              "Show diffs for transformed class matching the given class and instrumentator",
              "/diff/class-instr/java.util.List/org.mockito.*"),
          new Command(
              "/full-diff/class-instr/{pattern}/{instr}",
              Main::showClassDiffs,
              "Show diffs with all context for transformed class matching the given glob pattern and instrumentator",
              "/full-diff/class-instr/java.util.List/org.mockito.*"),
          new Command(
              "/decompile/{pattern}",
              Main::decompileClasses,
              "Decompile all transformed classes matching the given glob pattern",
              "/decompile/java.util.*"),
          new Command(
              "/all/decompile/{pattern}",
              Main::decompileClasses,
              "Decompile all classes matching the given glob pattern",
              "/all/decompile/java.util.stream.*"));

  private static final String HTML_HEADER =
      """
 <!DOCTYPE html>
         <head>
            <meta charset="utf-8" />
            <link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/highlight.js/10.7.1/styles/github.min.css" />
            <link
              rel="stylesheet"
              type="text/css"
              href="https://cdn.jsdelivr.net/npm/diff2html/bundles/css/diff2html.min.css"
            />
            <script src="https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/highlight.min.js"></script>
            <script src="https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/languages/java.min.js"></script>
            <script type="text/javascript" src="https://cdn.jsdelivr.net/npm/diff2html/bundles/js/diff2html-ui.min.js"></script>
            <style>
            body {
              margin: 1em;
            }
            select {
              margin-bottom: 1em;
            }
            </style>
          </head>
          <body>
          """;

  private static final String DECOMPILED_HTML_HEADER =
      HTML_HEADER
          + """
         <em>Decompiled bytecode using <a href="https://vineflower.org/">vineflower</a>, obtained
          when ever this page is loaded.</em>
          """;

  private static final String DECOMPILED_JAVAP_HEADER = HTML_HEADER + "<em>Decompiled bytecode using javap</em>";

  private static void runServer(int port) {
    System.out.println("Starting Javalin on port " + port);
    JavalinLogger.startupInfo = false;
    var app = Javalin.create();
    for (var command : commands) {
      for (var path : command.path) {
        app.get(path, command.handler);
      }
    }
    app.start(port);
  }

  private static void help(io.javalin.http.Context ctx) {
    ctx.contentType("text/html");
    ctx.result(
        HTML_HEADER
            + "<h1>Commands of Meta-Agent</h1><ul>"
            + commands.stream()
                .map(
                    c -> {
                      Function<String, String> formatPath =
                          p -> "<a href='" + p + "'>" + p + "</a>";
                      return "<li>"
                          + c.path.stream().map(formatPath).collect(Collectors.joining(","))
                          + ": "
                          + c.description
                          + " <ul><li>Example: "
                          + formatPath.apply(c.example)
                          + "</li></ul></li>";
                    })
                .collect(Collectors.joining())
            + "</ul></body>");
  }

  private static Pattern getMatchPattern(String pattern) {
    String regexp = pattern.replace(".", "\\.").replace("$", "\\$").replace("*", ".*");
    return Pattern.compile(regexp);
  }

  private static Pattern getMatchPattern(Context ctx) {
    if (ctx.pathParamMap().containsKey("pattern")) {
      return getMatchPattern(ctx.pathParam("pattern"));
    }
    return Pattern.compile(".*");
  }

  private static List<String> getInstrumentatorNames(Context ctx) {
    var pattern = getMatchPattern(ctx);
    return InstrumentationHandler.getInstrumentatorNames(pattern);
  }

  private static void listInstrumentators(Context ctx) {
    ctx.contentType("text/html");
    String listWithLinks =
        getInstrumentatorNames(ctx).stream()
            .map(
                instrumentator ->
                    "<li><a href='/diff/instrumentator/"
                        + instrumentator
                        + "'>"
                        + instrumentator
                        + "</a></li>")
            .collect(Collectors.joining());
    ctx.result(
        """
                    <!DOCTYPE html>
                    <h1>Instrumentators</h1>
                    <ul>
                    """
            + listWithLinks
            + """
                    </ul>
                    """);
  }

  @SuppressWarnings("unchecked")
  private static List<Class<?>> getClasses(Context ctx) {
    boolean all = ctx.path().contains("/all/");
    var pattern = getMatchPattern(ctx);
    Predicate<Class> classPredicate = c -> pattern.matcher(c.getName()).matches();
    Comparator<Class> classComparator = Comparator.comparing(Class::getName);
    var instrumented =
        InstrumentationHandler.getClassDiffs().keySet().stream()
            .filter(classPredicate)
            .sorted(classComparator)
            .toList();
    if (!all) {
      return instrumented;
    }
    return (List<Class<?>>)
        (Object)
            Stream.concat(
                    instrumented.stream(),
                    Arrays.stream(inst.getAllLoadedClasses())
                        .filter(classPredicate)
                        .sorted(classComparator))
                .toList();
  }

  private static void listClasses(Context ctx) {
    ctx.contentType("text/html");
    String listWithLinks =
        getClasses(ctx).stream()
            .map(
                clazz -> {
                  boolean instrumented = InstrumentationHandler.isInstrumented(clazz);
                  if (instrumented) {
                    return "<li><a href='/full-diff/class/"
                        + clazz.getName()
                        + "'>"
                        + clazz.getName()
                        + "</a></li>";
                  } else {
                    if (clazz.getName().startsWith("[")) {
                      return "";
                    }
                    if (inst.isModifiableClass(clazz)) {
                      return "<li><a href='/all/decompile/"
                          + clazz.getName()
                          + "'>"
                          + clazz.getName()
                          + " (only decompile)</a></li>";
                    }
                    return "<li>" + clazz.getName() + "</li>";
                  }
                })
            .collect(Collectors.joining());
    ctx.result(
        """
                    <!DOCTYPE html>
                    <h1>Classes</h1>
                    <ul>
                    """
            + listWithLinks
            + """
                    </ul>
                    """);
  }

  private static String getDecompiledHtmlHeader(DiffSourceMode mode) {
    return mode == DiffSourceMode.JAVA ? DECOMPILED_HTML_HEADER : DECOMPILED_JAVAP_HEADER;
  }

  private static void decompileClasses(Context ctx) {
    ctx.contentType("text/html");
    List<Class<?>> classes = getClasses(ctx);
    StringBuilder sb = new StringBuilder();
    DiffSourceMode mode = getMode(ctx);
    sb.append(getDecompiledHtmlHeader(mode));
    Map<Class<?>, byte[]> bytecodes =
        classes.stream()
            .filter(inst::isModifiableClass)
            .distinct()
            .collect(
                Collectors.toMap(
                    c -> c,
                    c -> {
                      if (InstrumentationHandler.isInstrumented(c)) {
                        return InstrumentationHandler.getCurrentBytecode(c);
                      }
                      return getBytecodeOfUnmodified(c);
                    }));
    System.out.println("Decompiling " + bytecodes.size() + " classes");
    Map<Class<?>, String> decompiledClasses = BytecodeDiffUtils.decompileClasses(bytecodes, mode);
    for (Class<?> c : classes) {
      String code = decompiledClasses.get(c);
      if (code == null) {
        continue;
      }
      sb.append("<h1>").append(c.getName()).append("</h1>");
      if (InstrumentationHandler.isInstrumented(c)) {
        sb.append(
            "<p>Instrumented: <a href='LINK'>LINK</a></p>"
                .replace("LINK", "/full-diff/class/" + c.getName()));
      }
      sb.append("<pre><code class='language-java'>");
      sb.append(makeCodeHtmlFriendly(code));
      sb.append("</code></pre>");
    }
    sb.append("<script>hljs.highlightAll();</script>");
    sb.append("</body>");
    ctx.result(sb.toString());
  }

  private static String makeCodeHtmlFriendly(String code) {
    return code.replace("<", "&lt;").replace(">", "&gt;");
  }

  private static DiffSourceMode getMode(Context ctx) {
    String modeParam = ctx.queryParam("mode");
    if (modeParam == null) {
      return DiffSourceMode.JAVA;
    }
    return switch (modeParam) {
      case "java" -> DiffSourceMode.JAVA;
      case "javap" -> DiffSourceMode.VERBOSE_BYTECODE;
      case "javap-verbose" -> DiffSourceMode.ULTRA_VERBOSE_BYTECODE;
      default -> throw new IllegalArgumentException("Invalid mode: " + modeParam);
    };
  }

  private static void showInstrumentatorDiffs(Context ctx) {
    ctx.contentType("text/html");
    StringBuilder sb = new StringBuilder();
    boolean fullDiff = ctx.path().contains("full-diff/");
    DiffSourceMode mode = getMode(ctx);
    sb.append(getDecompiledHtmlHeader(mode));
    for (String instrumentator : getInstrumentatorNames(ctx)) {
      var diffs = InstrumentationHandler.getInstrumentatorDiffs(instrumentator);
      sb.append("<h1>").append(instrumentator).append("</h1>");

      List<Class<?>> classesWithMultipleDiffs =
          diffs.getDiffs().entrySet().stream()
              .filter(e -> e.getValue().size() > 1)
              .map(Entry::getKey)
              .collect(Collectors.toList());
      if (!classesWithMultipleDiffs.isEmpty()) {
        sb.append("Classes with multiple diffs");
        sb.append("<ul>");
        for (var clazz : classesWithMultipleDiffs) {
          sb.append("<li><a href='/diff/class/")
              .append(clazz.getName())
              .append("'>")
              .append(clazz.getName())
              .append("</a></li>");
        }
        sb.append("</ul>");
        sb.append("Only showing the first diff for each class");
      }

      Map<Class<?>, BytecodeDiff> firstDiffs =
          diffs.getDiffs().entrySet().stream()
              .collect(
                  Collectors.toMap(
                      Map.Entry::getKey,
                      e -> {
                        var val = e.getValue().get(0);
                        return new BytecodeDiff(val.old(), val.current());
                      }));

      sb.append(formatDiff(BytecodeDiffUtils.diff(firstDiffs, mode, fullDiff), true));
    }
    sb.append("</body>");
    ctx.result(sb.toString());
  }

  private static void showClassDiffs(Context ctx) {
    ctx.contentType("text/html");
    StringBuilder sb = new StringBuilder();
    boolean fullDiff = ctx.path().contains("full-diff/");
    DiffSourceMode mode = getMode(ctx);
    Pattern instrPattern =
        ctx.pathParamMap().containsKey("instr")
            ? getMatchPattern(ctx.pathParam("instr"))
            : Pattern.compile(".*");
    sb.append(getDecompiledHtmlHeader(mode));
    for (Class<?> clazz : getClasses(ctx)) {
      sb.append("<h1>").append(clazz.getName()).append("</h1>");
      for (var diff : InstrumentationHandler.getClassDiffs().get(clazz).getDiffs()) {
        if (!instrPattern.matcher(diff.instrumentator().name()).matches()) {
          continue;
        }
        sb.append("<h2>").append(diff.instrumentator().name()).append("</h2>");
        sb.append(
            formatDiff(
                BytecodeDiffUtils.diff(
                    Map.of(clazz, new BytecodeDiff(diff.old(), diff.current())), mode, fullDiff),
                false));
      }
    }
    sb.append("</body>");
    ctx.result(sb.toString());
  }

  private static final AtomicLong diffIdCounter = new AtomicLong(0);

  private static String formatDiff(String diff, boolean drawFileList) {
    long id = diffIdCounter.getAndIncrement();
    return """
    Output format:
    <select onchange='showID()' id='output-mode-ID'>
    <option value="line-by-line" selected='selected'>Line by Line</option>
    <option value="side-by-side">Side by Side</option>
    </select>
    <div style='display: none'>
    <pre id="diff-ID">
    DIFF
    </pre>
    </div>
    <script>
    function showID() {
        var diffString = document.getElementById('diff-ID').innerText;
        var outputMode = document.getElementById('output-mode-ID').value;
        var targetElement = document.getElementById('show-ID');
        var configuration = {
          drawFileList: DRAW_FILE_LIST,
          fileListToggle: false,
          fileListStartVisible: false,
          fileContentToggle: false,
          matching: 'lines',
          outputFormat: outputMode,
          synchronisedScroll: true,
          highlight: true,
          renderNothingWhenEmpty: false,
          rawTemplates: {}
        };
        var diff2htmlUi = new Diff2HtmlUI(targetElement, diffString, configuration);
        diff2htmlUi.draw();
        diff2htmlUi.highlightCode();
    }
    document.addEventListener('DOMContentLoaded', showID);
    </script>
    <div id="show-ID"></div>
    """
        .replace("DIFF", makeCodeHtmlFriendly(diff))
        .replace("ID", Long.toString(id))
        .replace("DRAW_FILE_LIST", Boolean.toString(drawFileList));
  }

  private static Path getExtractedJARPath() throws IOException {
    try (InputStream in = Main.class.getClassLoader().getResourceAsStream("meta-runtime.jar")) {
      if (in == null) {
        throw new RuntimeException("Could not find meta-runtime.jar");
      }
      File file = File.createTempFile("runtime", ".jar");
      file.deleteOnExit();
      Files.copy(in, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
      return file.toPath().toAbsolutePath();
    }
  }

  private static byte[] getBytecodeOfUnmodified(Class clazz) {
    byte[][] bytes = {null};
    var transformer =
        new ClassFileTransformer() {
          @Override
          public byte[] transform(
              ClassLoader loader,
              String className,
              Class<?> classBeingRedefined,
              java.security.ProtectionDomain protectionDomain,
              byte[] classfileBuffer) {
            if (classBeingRedefined.equals(clazz)) {
              bytes[0] = classfileBuffer;
            }
            return classfileBuffer;
          }
        };
    inst.addTransformer(transformer, true);
    try {
      inst.retransformClasses(clazz);
    } catch (Exception e) {
      e.printStackTrace();
    }
    var time = System.currentTimeMillis();
    while (bytes[0] == null && System.currentTimeMillis() - time < 100) {
      try {
        Thread.sleep(10);
      } catch (InterruptedException e) {
        e.printStackTrace();
      }
    }
    inst.removeTransformer(transformer);
    return bytes[0];
  }
}
