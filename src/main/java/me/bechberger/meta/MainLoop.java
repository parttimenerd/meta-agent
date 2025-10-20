package me.bechberger.meta;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import me.bechberger.meta.NavigationUtils.Action;
import me.bechberger.meta.runtime.InstrumentationHandler;
import me.bechberger.meta.runtime.Klass;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Takes care of the main loop of the agent, including the server
 * <p>
 * I had to split this class to prevent the {@link Klass} being loaded before the runtime.jar
 * is on the bootstrap classpath.
 */
public class MainLoop {

    private static Instrumentation inst;
    private static int serverPort = 7071; // Track server port for navigation

    static void run(Main.Options options, Instrumentation inst) {
        serverPort = options.port;
        inst.addTransformer(new ClassTransformer(options.callbackClasses), true);
        MainLoop.inst = inst;
        // transform all loaded classes
        triggerRetransformOfAllClasses(inst);
        // start server
        if (options.server) {
            Thread thread =
                    new Thread(
                            () -> runServer(options.port));
            thread.setDaemon(true);
            thread.start();
        }
    }

    private static void triggerRetransformOfAllClasses(Instrumentation inst) {
        for (var clazz : inst.getAllLoadedClasses()) {
            if (clazz.isInterface() || !ClassTransformer.canTransformClass(clazz)) {
                continue;
            }
            try {
                inst.retransformClasses(clazz);
            } catch (UnmodifiableClassException e) {
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private record Response(String response, boolean html) {
    }

    private record Command(List<String> path, Function<HttpExchange, Response> handler, String description,
                           String example) {
        Command(String path, Function<HttpExchange, Response> handler, String description, String example) {
            this(List.of(path), handler, description, example);
        }
    }

    private static final List<Command> commands =
            List.of(
                    new Command(List.of("/", "/help"), MainLoop::help, "Show this help", "/help"),
                    new Command(
                            "/instrumentators",
                            MainLoop::listInstrumentators,
                            "List all instrumentators",
                            "/instrumentators"),
                    new Command(
                            "/instrumentators?pattern={pattern}",
                            MainLoop::listInstrumentators,
                            "List instrumentators matching the given glob pattern",
                            "/instrumentators?pattern=org.mockito.*"),
                    new Command(
                            "/diff/instrumentator?pattern={pattern}",
                            MainLoop::showInstrumentatorDiffs,
                            "Show diffs for instrumentator matching the given glob pattern",
                            "/diff/instrumentator?pattern=org.mockito.*"),
                    new Command(
                            "/full-diff/instrumentator?pattern={pattern}",
                            MainLoop::showInstrumentatorDiffs,
                            "Show diffs with all context for instrumentator matching the given glob pattern",
                            "/full-diff/instrumentator?pattern=org.mockito.*"),
                    new Command("/classes", MainLoop::listClasses, "List all classes", "/classes"),
                    new Command(
                            "/classes?pattern={pattern}",
                            MainLoop::listClasses,
                            "List transformed classes matching the given glob pattern",
                            "/classes?pattern=java.util.*"),
                    new Command("/all/classes", MainLoop::listClasses, "List all classes", "/all/classes"),
                    new Command(
                            "/all/classes?pattern={pattern}",
                            MainLoop::listClasses,
                            "List all classes matching the given glob pattern",
                            "/all/classes?pattern=java.util.*"),
                    new Command(
                            "/diff/class?pattern={pattern}",
                            MainLoop::showClassDiffs,
                            "Show diffs for transformed classes matching the given glob pattern",
                            "/diff/class?pattern=java.util.*"),
                    new Command(
                            "/full-diff/class?pattern={pattern}",
                            MainLoop::showClassDiffs,
                            "Show diffs with all context for transformed classes matching the given glob pattern",
                            "/full-diff/class?pattern=java.util.*"),
                    new Command(
                            "/diff/class-instr?pattern={pattern}&instr={instr}",
                            MainLoop::showClassDiffs,
                            "Show diffs for transformed class matching the given class and instrumentator",
                            "/diff/class-instr?pattern=java.util.List&instr=org.mockito.*"),
                    new Command(
                            "/full-diff/class-instr?pattern={pattern}&instr={instr}",
                            MainLoop::showClassDiffs,
                            "Show diffs with all context for transformed class matching the given glob pattern and instrumentator",
                            "/full-diff/class-instr?pattern=java.util.List&instr=org.mockito.*"),
                    new Command(
                            "/decompile?pattern={pattern}",
                            MainLoop::decompileClasses,
                            "Decompile all transformed classes matching the given glob pattern",
                            "/decompile?pattern=java.util.*"),
                    new Command(
                            "/all/decompile?pattern={pattern}",
                            MainLoop::decompileClasses,
                            "Decompile all classes matching the given glob pattern",
                            "/all/decompile?pattern=java.util.stream.*"));

    /**
     * Generate HTML header with navigation for a specific page
     */
    private static String getHTMLHeader(HttpExchange exchange) {
        String currentPath = exchange.getRequestURI().getPath();
        return getHTMLHeader(currentPath);
    }

    /**
     * Generate HTML header with navigation and optional stats
     */
    private static String getHTMLHeader(String currentPath) {
        return """
                <!DOCTYPE html>
                <html>
                <head>
                   <meta charset="utf-8" />
                   <title>Meta-Agent - Bytecode Instrumentation Inspector</title>
                   <link rel="stylesheet" href="/file/meta-agent.css" />
                   <link rel="stylesheet" href="/file/highlight.css" />
                   <link rel="stylesheet" type="text/css" href="/file/diff2html.css" />
                   <script src="/file/highlight.js"></script>
                   <script src="/file/highlight.java.js"></script>
                   <script src="/file/diff2html.js"></script>
                </head>
                <body>
                """ + NavigationUtils.getNavigationHeader(currentPath, serverPort) + """
                <div class="main-content">
                """;
    }

    private static void runServer(int port) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
            System.out.println("Server started at localhost:" + port);
            Set<String> paths = new HashSet<>();
            for (var command : commands) {
                for (var path : command.path) {
                    String actualPath = path.split("\\?")[0];
                    if (paths.contains(actualPath)) {
                        continue;
                    }
                    paths.add(actualPath);
                    server.createContext(actualPath, new MyHandler(command.handler));
                }
            }
            server.createContext("/file/", getFileHTTPHandler());
            server.setExecutor(null); // creates a default executor
            server.start();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static final Map<String, String> suffixToMime = Map.of("css", "text/css", "js", "text/javascript");

    @NotNull
    private static HttpHandler getFileHTTPHandler() {
        return exchange -> {
            String name = exchange.getRequestURI().getPath().substring("/file/".length());
            Path file = extractFile(name);
            if (!Files.exists(file)) {
                exchange.sendResponseHeaders(404, 0);
                exchange.getResponseBody().close();
                return;
            }

            var suffix = name.substring(name.lastIndexOf(".") + 1);
            exchange.getResponseHeaders().add("Content-Type", suffixToMime.getOrDefault(suffix, "text/plain"));
            exchange.sendResponseHeaders(200, Files.size(file));
            Files.copy(file, exchange.getResponseBody());
            exchange.getResponseBody().close();
        };
    }

    static class MyHandler implements HttpHandler {
        private final Function<HttpExchange, Response> handler;

        MyHandler(Function<HttpExchange, Response> handler) {
            this.handler = handler;
        }

        @Override
        public void handle(HttpExchange t) throws IOException {
            Response response;
            try {
                response = this.handler.apply(t);
            } catch (Throwable e) {
                e.printStackTrace();
                t.getResponseHeaders().add("Content-Type", "text/plain");
                t.sendResponseHeaders(500, 0);
                OutputStream os = t.getResponseBody();
                os.write(e.getMessage().getBytes());
                os.close();
                return;
            }
            if (response.html) {
                t.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            } else {
                t.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
            }
            byte[] responseBytes = response.response.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            t.sendResponseHeaders(200, responseBytes.length);
            OutputStream os = t.getResponseBody();
            os.write(responseBytes);
            os.close();
        }
    }

    private static Response help(HttpExchange exchange) {
        if (outputRaw(exchange)) {
            return rawHelp(exchange);
        }

        String breadcrumbs = NavigationUtils.getBreadcrumbs(exchange);

        return new Response(getHTMLHeader(exchange)
                + breadcrumbs
                + "<h1>Commands</h1>"
                + "<p>Available endpoints for inspecting bytecode instrumentation:</p>"
                + "<table>"
                + "<tr><th>Path</th><th>Description</th><th>Example</th></tr>"
                + commands.stream()
                .map(
                        c -> {
                            Function<String, String> formatPath =
                                    p -> "<a href='" + p + "'>" + p + "</a>";
                            return "<tr><td>"
                                    + c.path.stream().map(formatPath).collect(Collectors.joining(","))
                                    + "</td><td>"
                                    + c.description
                                    + "</td><td>"
                                    + formatPath.apply(c.example)
                                    + "</td></tr>";
                        })
                .collect(Collectors.joining())
                + "</table>" +
                "<h2>Additional Options</h2>" +
                "<p><strong>Decompile modes:</strong> " + Stream.of(DiffSourceMode.supportedValues())
                .map(m -> "<code>?mode=" + m.param + "</code>")
                .collect(Collectors.joining(", ")) + "</p>" +
                "<p><strong>Raw output:</strong> Add <code>?output=raw</code> to get plain text output instead of HTML</p>" +
                "</div></body></html>", true);
    }

    private static Response rawHelp(HttpExchange exchange) {
        // plain text version of help, including decompile modes
        // in the format "Path: (align)Description (Example)"
        String commandHelp =
                commands.stream()
                        .map(
                                c -> {
                                    Function<String, String> formatPath =
                                            p -> p + ":\n  " + c.description + "\n  Example: " + c.example + ")";
                                    return c.path.stream().map(formatPath).collect(Collectors.joining("\n"));
                                })
                        .collect(Collectors.joining("\n"));
        String modeHelp = Stream.of(DiffSourceMode.supportedValues())
                .map(m -> "?mode=" + m.param)
                .collect(Collectors.joining(", "));
        String outputHelp = "?output=raw";
        return new Response(
                "Commands of Meta-Agent\n" + commandHelp + "\nDecompile modes: " + modeHelp + "\nGet raw version of the output: " + outputHelp, false);
    }

    private static Pattern getMatchPattern(String pattern) {
        String regexp = pattern.replace(".", "\\.").replace("$", "\\$").replace("*", ".*");
        return Pattern.compile(regexp);
    }

    private static Pattern getMatchPattern(HttpExchange exchange) {
        Map<String, String> params = getURLParameters(exchange);
        if (params.containsKey("pattern")) {
            return getMatchPattern(params.get("pattern"));
        }
        return Pattern.compile(".*");
    }

    private static List<String> getInstrumentatorNames(HttpExchange exchange) {
        var pattern = getMatchPattern(exchange);
        return InstrumentationHandler.getInstrumentatorNames(pattern);
    }

    private static boolean outputRaw(HttpExchange exchange) {
        return getURLParameters(exchange).containsKey("output") && getURLParameters(exchange).get("output").equals("raw");
    }

    private static Response listInstrumentators(HttpExchange exchange) {
        var instrs = getInstrumentatorNames(exchange);
        if (outputRaw(exchange)) {
            return new Response(String.join("\n", instrs), false);
        }

        String breadcrumbs = NavigationUtils.getBreadcrumbs(exchange);
        Map<String, String> params = getURLParameters(exchange);
        List<Action> quickActions = NavigationUtils.buildQuickActions(exchange.getRequestURI().getPath(), params);

        String contextBar = NavigationUtils.getContextBar(exchange, instrs.size(), quickActions);

        String listWithLinks =
                instrs.stream()
                        .map(
                                instrumentator ->
                                        "<li><a href='/diff/instrumentator?pattern="
                                                + instrumentator
                                                + "'>"
                                                + instrumentator
                                                + "</a> <span class='text-muted'>→ View diffs</span></li>")
                        .collect(Collectors.joining());

        return new Response(
                getHTMLHeader(exchange)
                        + breadcrumbs
                        + contextBar
                        + "<h1>Instrumentators</h1>"
                        + "<ul>"
                        + listWithLinks
                        + "</ul>"
                        + "</div></body></html>", true);
    }

    @SuppressWarnings("unchecked")
    private static List<Klass> getClasses(HttpExchange exchange) {
        boolean all = exchange.getRequestURI().getPath().contains("/all/");
        var pattern = getMatchPattern(exchange);
        Predicate<Klass> classPredicate = c -> pattern.matcher(c.getName()).matches();
        Comparator<Klass> classComparator = Comparator.comparing(Klass::getName);
        var instrumented =
                InstrumentationHandler.getClassDiffs().keySet().stream()
                        .filter(classPredicate)
                        .sorted(classComparator)
                        .toList();
        if (!all) {
            return instrumented;
        }
        return (List<Klass>)
                (Object)
                        Stream.concat(
                                        instrumented.stream(),
                                        Arrays.stream(inst.getAllLoadedClasses())
                                                .map(Klass::new)
                                                .filter(classPredicate)
                                                .sorted(classComparator))
                                .toList();
    }

    private static Response listClasses(HttpExchange exchange) {
        boolean raw = outputRaw(exchange);
        List<Klass> classes = getClasses(exchange);

        String listWithLinks =
                classes.stream()
                        .map(
                                clazz -> {
                                    boolean instrumented = InstrumentationHandler.isInstrumented(clazz);
                                    if (instrumented) {
                                        if (raw) {
                                            return clazz.getName() + ", /full-diff/class?pattern="
                                                    + clazz.getName();
                                        }
                                        return "<li><a href='/full-diff/class?pattern="
                                                + clazz.getName()
                                                + "'>"
                                                + clazz.getName()
                                                + "</a> <span class='text-muted'>→ View diffs</span></li>";
                                    } else {
                                        if (clazz.getName().startsWith("[")) {
                                            return "";
                                        }
                                        if (clazz.klass() == null || inst.isModifiableClass(clazz.klass())) {
                                            if (raw) {
                                                return clazz.getName() + ", /all/decompile?pattern="
                                                        + clazz.getName();
                                            }
                                            return "<li><a href='/all/decompile?pattern="
                                                    + clazz.getName()
                                                    + "'>"
                                                    + clazz.getName()
                                                    + "</a> <span class='text-muted'>→ Decompile only</span></li>";
                                        }
                                        if (raw) {
                                            return clazz.getName();
                                        }
                                        return "<li>" + clazz.getName() + " <span class='text-muted'>(not modifiable)</span></li>";
                                    }
                                })
                        .collect(Collectors.joining());
        if (raw) {
            return new Response(listWithLinks, false);
        }

        String breadcrumbs = NavigationUtils.getBreadcrumbs(exchange);
        Map<String, String> params = getURLParameters(exchange);
        List<Action> quickActions = NavigationUtils.buildQuickActions(exchange.getRequestURI().getPath(), params);
        String contextBar = NavigationUtils.getContextBar(exchange, classes.size(), quickActions);

        boolean all = exchange.getRequestURI().getPath().contains("/all/");
        String title = all ? "All Classes" : "Transformed Classes";

        return new Response(
                getHTMLHeader(exchange)
                        + breadcrumbs
                        + contextBar
                        + "<h1>" + title + "</h1>"
                        + "<ul>"
                        + listWithLinks
                        + "</ul>"
                        + "</div></body></html>", true);
    }

    private static Map<String, String> getURLParameters(HttpExchange exchange) {
        String query = exchange.getRequestURI().getQuery();
        if (query == null) {
            return Map.of();
        }
        return Arrays.stream(query.split("&"))
                .map(s -> s.split("="))
                .collect(Collectors.toMap(a -> a[0], a -> a[1]));
    }

    private static String combineURL(HttpExchange exchange, Map<String, String> parameters) {
        String url = exchange.getRequestURI().toString();
        String query = parameters.entrySet().stream().sorted(
                Comparator.comparing((Entry<String, String> e) -> e.getKey().equals("mode"))
                        .thenComparing(e -> e.getKey().equals("output"))
        ).map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining("&"));
        return url.split("\\?")[0] + "?" + query;
    }

    private static String getDecompiledHtmlHeader(HttpExchange exchange, DiffSourceMode mode, int classCount) {
        String breadcrumbs = NavigationUtils.getBreadcrumbs(exchange);
        Map<String, String> params = getURLParameters(exchange);

        // Build mode selector dropdown
        StringBuilder modeSelector = new StringBuilder();
        modeSelector.append("<label for='mode-selector'>Mode</label>");
        modeSelector.append("<select id='mode-selector' onchange='window.location.href=this.value'>");
        for (DiffSourceMode m : DiffSourceMode.supportedValues()) {
            Map<String, String> newParams = new HashMap<>(params);
            newParams.put("mode", m.param);
            String url = combineURL(exchange, newParams);
            String selected = (m == mode) ? " selected" : "";
            modeSelector.append("<option value='").append(url).append("'").append(selected).append(">")
                       .append(m.param).append("</option>");
        }
        modeSelector.append("</select>");

        // Add raw output action
        Map<String, String> rawParams = new HashMap<>(params);
        rawParams.put("output", "raw");
        List<Action> quickActions = Collections.singletonList(new Action("Raw Output", combineURL(exchange, rawParams)));

        String contextBar = NavigationUtils.getContextBar(
                exchange,
                classCount,
                modeSelector.toString(),
                quickActions
        );

        return getHTMLHeader(exchange)
                + breadcrumbs
                + contextBar
                + mode.htmlHeader;
    }

    private static Response decompileClasses(HttpExchange exchange) {
        boolean raw = outputRaw(exchange);
        List<Klass> classes = getClasses(exchange);
        DiffSourceMode mode = getMode(exchange);
        Map<Klass, byte[]> bytecodes =
                classes.stream()
                        .filter(k -> k.klass() == null || inst.isModifiableClass(k.klass()))
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
        Map<Klass, String> decompiledClasses = Decompilation.decompileClasses(bytecodes, mode);
        StringBuilder sb = new StringBuilder();
        if (!raw) {
            sb.append(getDecompiledHtmlHeader(exchange, mode, decompiledClasses.size()));
        }
        for (Klass c : classes) {
            String code = decompiledClasses.get(c);
            if (code == null) {
                continue;
            }
            if (raw) {
                sb.append("##### ").append(c.getName()).append("\n");
                sb.append(code).append("\n");
                continue;
            }
            sb.append("<h2>").append(c.getName()).append("</h2>");
            if (InstrumentationHandler.isInstrumented(c)) {
                sb.append(
                        "<p><a href='LINK'>View bytecode diffs for this class</a></p>"
                                .replace("LINK", "/full-diff/class?pattern=" + c.getName()));
            }
            sb.append("<pre><code class='language-java'>");
            sb.append(makeCodeHtmlFriendly(code));
            sb.append("</code></pre>");
        }
        if (!raw) {
            sb.append("<script>hljs.highlightAll();</script>");
            sb.append("</div></body></html>");
        }
        return new Response(sb.toString(), !raw);
    }

    private static String makeCodeHtmlFriendly(String code) {
        return code.replace("<", "&lt;").replace(">", "&gt;");
    }

    private static DiffSourceMode getMode(HttpExchange exchange) {
        Map<String, String> params = getURLParameters(exchange);
        String modeParam = params.get("mode");
        if (modeParam == null) {
            return DiffSourceMode.JAVA;
        }
        return Stream.of(DiffSourceMode.supportedValues())
                .filter(m -> m.param.equalsIgnoreCase(modeParam))
                .findFirst()
                .orElseThrow(
                        () -> {
                            String message = "Invalid decompilation mode: " + modeParam;
                            System.err.println(message);
                            return new IllegalArgumentException(message);
                        });
    }

    private static Response showInstrumentatorDiffs(HttpExchange exchange) {
        boolean raw = outputRaw(exchange);
        boolean fullDiff = exchange.getRequestURI().getPath().contains("full-diff/");
        DiffSourceMode mode = getMode(exchange);
        var instrumentators = getInstrumentatorNames(exchange);

        StringBuilder sb = new StringBuilder();
        if (!raw) {
            int totalClasses = instrumentators.stream()
                    .mapToInt(i -> InstrumentationHandler.getInstrumentatorDiffs(i).getDiffs().size())
                    .sum();
            sb.append(getDecompiledHtmlHeader(exchange, mode, totalClasses));
        }

        for (String instrumentator : instrumentators) {
            var diffs = InstrumentationHandler.getInstrumentatorDiffs(instrumentator);
            if (raw) {
                sb.append("##### ").append(instrumentator).append("\n");
            } else {
                sb.append("<h2>").append(instrumentator).append("</h2>");
                sb.append("<p>Affects <strong>").append(diffs.getDiffs().size()).append("</strong> ")
                  .append(diffs.getDiffs().size() == 1 ? "class" : "classes").append("</p>");

                List<Klass> classesWithMultipleDiffs =
                        diffs.getDiffs().entrySet().stream()
                                .filter(e -> e.getValue().size() > 1)
                                .map(Entry::getKey)
                                .toList();
                if (!classesWithMultipleDiffs.isEmpty()) {
                    sb.append("<p><strong>Note:</strong> Classes with multiple diffs (showing first diff only):</p>");
                    sb.append("<ul>");
                    for (var clazz : classesWithMultipleDiffs) {
                        sb.append("<li><a href='/diff/class?pattern=")
                                .append(clazz.getName())
                                .append("'>")
                                .append(clazz.getName())
                                .append("</a> <span class='text-muted'>→ View all diffs</span></li>");
                    }
                    sb.append("</ul>");
                }
            }

            Map<Klass, SimpleBytecodeDiff> firstDiffs =
                    diffs.getDiffs().entrySet().stream()
                            .collect(
                                    Collectors.toMap(
                                            Entry::getKey,
                                            e -> {
                                                var val = e.getValue().get(0);
                                                return new SimpleBytecodeDiff(val.old(), val.current());
                                            }));
            var diff = BytecodeDiffUtils.diff(firstDiffs, mode, fullDiff);
            if (raw) {
                sb.append("##### patch instrumentator ").append(instrumentator).append("\n");
                sb.append(diff).append("\n");
                for (var clazz : diffs.getDiffs().keySet()) {
                    var old = diffs.getDiffs().get(clazz).get(0).old();
                    var current = diffs.getDiffs().get(clazz).get(diffs.getDiffs().get(clazz).size() - 1).current();
                    sb.append("##### patch class ").append(clazz.getName()).append("\n");
                    sb.append(BytecodeDiffUtils.diff(
                            Map.of(clazz, new SimpleBytecodeDiff(old, current)),
                            mode, fullDiff)).append("\n");
                    sb.append("##### old class ").append(clazz.getName()).append("\n");
                    sb.append(Decompilation.decompileClasses(Map.of(clazz, old), mode).get(clazz)).append("\n");
                    sb.append("##### new class ").append(clazz.getName()).append("\n");
                    sb.append(Decompilation.decompileClasses(Map.of(clazz, current), mode).get(clazz)).append("\n");
                }
            } else {
                sb.append(formatDiff(diff, true));
            }
        }
        if (!raw) {
            sb.append("<script>hljs.highlightAll();</script>");
            sb.append("</div></body></html>");
        }
        return new Response(sb.toString(), !raw);
    }

    private static Response showClassDiffs(HttpExchange exchange) {
        boolean fullDiff = exchange.getRequestURI().getPath().contains("full-diff/");
        DiffSourceMode mode = getMode(exchange);
        Map<String, String> params = getURLParameters(exchange);
        Pattern instrPattern = params.containsKey("instr") ? getMatchPattern(params.get("instr")) : Pattern.compile(".*");
        boolean raw = outputRaw(exchange);
        List<Klass> classes = getClasses(exchange);

        StringBuilder sb = new StringBuilder();
        if (!raw) {
            int totalDiffs = classes.stream()
                    .mapToInt(c -> (int) InstrumentationHandler.getClassDiffs().get(c).getDiffs().stream()
                            .filter(d -> instrPattern.matcher(d.instrumentator().name()).matches())
                            .count())
                    .sum();
            sb.append(getDecompiledHtmlHeader(exchange, mode, classes.size()));
        }

        for (Klass clazz : classes) {
            var classDiffs = InstrumentationHandler.getClassDiffs().get(clazz).getDiffs().stream()
                    .filter(d -> instrPattern.matcher(d.instrumentator().name()).matches())
                    .toList();

            if (classDiffs.isEmpty()) {
                continue;
            }

            if (raw) {
                sb.append("##### ").append(clazz.getName()).append("\n");
            } else {
                sb.append("<h2>").append(clazz.getName()).append("</h2>");
                sb.append("<p>Modified by <strong>").append(classDiffs.size()).append("</strong> ")
                  .append(classDiffs.size() == 1 ? "instrumentator" : "instrumentators").append("</p>");
            }

            for (var diff : classDiffs) {
                if (raw) {
                    sb.append("##### patch instrumentator ").append(diff.instrumentator().name()).append("\n");
                    sb.append(BytecodeDiffUtils.diff(
                            Map.of(clazz, new SimpleBytecodeDiff(diff.old(), diff.current())), mode, fullDiff)).append("\n");
                    sb.append("##### old class ").append(clazz.getName()).append("\n");
                    sb.append(Decompilation.decompileClasses(Map.of(clazz, diff.old()), mode).get(clazz)).append("\n");
                    sb.append("##### new class ").append(clazz.getName()).append("\n");
                    sb.append(Decompilation.decompileClasses(Map.of(clazz, diff.current()), mode).get(clazz)).append("\n");
                } else {
                    sb.append("<h3>").append(diff.instrumentator().name()).append("</h3>");
                    sb.append(
                            formatDiff(
                                    BytecodeDiffUtils.diff(
                                            Map.of(clazz, new SimpleBytecodeDiff(diff.old(), diff.current())), mode, fullDiff),
                                    false));
                }
            }
        }
        if (!raw) {
            sb.append("</div></body></html>");
        }
        return new Response(sb.toString(), !raw);
    }

    private static final AtomicLong diffIdCounter = new AtomicLong(0);

    private static String formatDiff(String diff, boolean drawFileList) {
        long id = diffIdCounter.getAndIncrement();
        return """
                Output format:
                <select onchange='showID()' id='output-mode-ID'>
                <option value="line-by-line">Line by Line</option>
                <option value="side-by-side" selected='selected'>Side by Side</option>
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


    private static byte[] getBytecodeOfUnmodified(Klass clazz) {
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
                        if (className.equals(clazz.name())) {
                            bytes[0] = classfileBuffer;
                        }
                        return classfileBuffer;
                    }
                };
        inst.addTransformer(transformer, true);
        try {
            if (clazz.hasClass()) {
                inst.retransformClasses(clazz.klass());
            } else {
                inst.retransformClasses(Class.forName(clazz.getName()));
            }
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

    private static Path extractFile(String name) {
        try {
            Path path = Files.createTempFile("meta-agent", name);
            path.toFile().deleteOnExit();
            try (InputStream in = MainLoop.class.getClassLoader().getResourceAsStream(name)) {
                if (in == null) {
                    throw new RuntimeException("Could not find " + name);
                }
                Files.copy(in, path, StandardCopyOption.REPLACE_EXISTING);
            }
            return path;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}