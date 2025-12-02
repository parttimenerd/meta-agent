package me.bechberger.meta;

import static java.util.stream.Collectors.joining;

import com.sun.net.httpserver.HttpExchange;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Utilities for generating navigation elements (header, breadcrumbs, context bar) for the web interface
 */
public class NavigationUtils {
    /**
     * Get the main navigation header that appears on every page
     * @param currentPath The current request path
     * @return HTML for the navigation header
     */
    public static String getNavigationHeader(String currentPath) {
        // Map navigation items to their active states
        Map<String, Boolean> navStates = Map.of(
            "HOME_CLASS", isActive(currentPath, "/", "/help"),
            "INSTRS_CLASS", isInstrumentatorsActive(currentPath),
            "CLASSES_CLASS", isClassesActive(currentPath),
            "ALL_CLASSES_CLASS", currentPath.startsWith("/all/")
        );

        String template = """
            <nav class="nav-header">
                <div class="nav-left">
                    <a href="/" class="title">Meta-Agent</a>
                    <span class="subtitle">Bytecode Instrumentation Inspector</span>
                </div>
                <div class="nav-links">
                    <a href="/instrumentators" $INSTRS_CLASS$>Instrumentators</a>
                    <a href="/classes" $CLASSES_CLASS$>Classes</a>
                    <a href="/all/classes" $ALL_CLASSES_CLASS$>All Classes</a>
                </div>
                <div class="nav-right">
                    <span class="meta-info">
                        <a href="https://github.com/parttimenerd/meta-agent" title="GitHub Repository">GitHub</a>
                    </span>
                </div>
            </nav>
            """;

        String result = template;
        for (Map.Entry<String, Boolean> entry : navStates.entrySet()) {
            result = result.replace("$" + entry.getKey() + "$", getActiveClass(entry.getValue()));
        }
        return result;
    }

    /**
     * Get breadcrumbs showing the current location
     * @param exchange The HTTP exchange
     * @param additionalContext Additional context items to display (e.g., "Java Classes", "42 matches")
     * @return HTML for breadcrumbs
     */
    public static String getBreadcrumbs(HttpExchange exchange, String... additionalContext) {
        String path = exchange.getRequestURI().getPath();
        Map<String, String> params = getURLParameters(exchange);

        StringBuilder breadcrumbs = new StringBuilder();
        breadcrumbs.append("<div class='breadcrumbs'>");

        // Parse the path to build breadcrumb trail
        if (isInstrumentatorsPath(path)) {
            appendBreadcrumb(breadcrumbs, "/instrumentators", "Instrumentators");
            if (params.containsKey("pattern")) {
                appendBreadcrumb(breadcrumbs, null, escapeHtml(params.get("pattern")), "current");
            }
            if (path.contains("/diff/")) {
                appendBreadcrumb(breadcrumbs, null, "Diffs", "current");
            }
        } else if (isClassesPath(path)) {
            boolean all = path.startsWith("/all/");
            String classesLink = all ? "/all/classes" : "/classes";
            String classesLabel = all ? "All Classes" : "Classes";
            appendBreadcrumb(breadcrumbs, classesLink, classesLabel);

            if (params.containsKey("pattern")) {
                appendBreadcrumb(breadcrumbs, null, escapeHtml(params.get("pattern")), "current");
            }
        } else if (isDiffPath(path)) {
            appendBreadcrumb(breadcrumbs, "/classes", "Classes");

            if (params.containsKey("pattern")) {
                appendBreadcrumb(breadcrumbs, null, escapeHtml(params.get("pattern")), "current");
            }
            appendBreadcrumb(breadcrumbs, null, "Diffs", "current");
        } else if (isDecompilePath(path)) {
            appendBreadcrumb(breadcrumbs, null, "Decompile", "current");

            if (params.containsKey("pattern")) {
                appendBreadcrumb(breadcrumbs, null, escapeHtml(params.get("pattern")), "current");
            }
        }

        // Add any additional context
        for (String context : additionalContext) {
            if (context != null && !context.isEmpty()) {
                appendBreadcrumb(breadcrumbs, null, escapeHtml(context), "context");
            }
        }

        breadcrumbs.append("</div>");
        return breadcrumbs.toString();
    }

    /**
     * Append a breadcrumb item to the breadcrumb trail
     */
    private static void appendBreadcrumb(StringBuilder sb, String href, String label) {
        appendBreadcrumb(sb, href, label, null);
    }

    /**
     * Append a breadcrumb item to the breadcrumb trail with optional CSS class
     */
    private static void appendBreadcrumb(StringBuilder sb, String href, String label, String cssClass) {
        sb.append("<span class='separator'>›</span>");
        if (href != null) {
            sb.append("<a href='").append(href).append("'>");
        }
        if (cssClass != null) {
            sb.append("<span class='").append(cssClass).append("'>");
        }
        sb.append(label);
        if (cssClass != null) {
            sb.append("</span>");
        }
        if (href != null) {
            sb.append("</a>");
        }
    }

    private static boolean isInstrumentatorsPath(String path) {
        return path.startsWith("/instrumentators") || path.contains("/instrumentator");
    }

    private static boolean isClassesPath(String path) {
        return path.startsWith("/classes") || path.startsWith("/all/classes");
    }

    private static boolean isDiffPath(String path) {
        return path.contains("/diff/class") || path.contains("/full-diff/class");
    }

    private static boolean isDecompilePath(String path) {
        return path.startsWith("/decompile") || path.startsWith("/all/decompile");
    }

    public static String getContextBar(HttpExchange exchange, int resultCount, List<Action> actions) {
        return getContextBar(exchange, resultCount, null, actions);
    }

    /**
     * Get context bar showing current filter, custom HTML element, and quick actions
     * @param exchange The HTTP exchange
     * @param resultCount Number of results (use -1 to skip displaying count)
     * @param customElement Custom HTML element to insert (e.g., a dropdown selector)
     * @param actions Quick action buttons (as key-value pairs: "label:url")
     * @return HTML for context bar
     */
    public static String getContextBar(HttpExchange exchange, int resultCount, String customElement, List<Action> actions) {
        String filterDesc = getActiveFilterDescription(exchange);

        StringBuilder contextBar = new StringBuilder();
        contextBar.append("<div class='context-bar'>");

        // Left side: filter info
        contextBar.append("<div class='filter-info'>");
        if (resultCount >= 0) {
            contextBar.append("Showing <strong>").append(resultCount).append("</strong> ")
                      .append(resultCount == 1 ? "result" : "results");
            if (!filterDesc.isEmpty()) {
                contextBar.append(" matching ");
            }
        }
        contextBar.append(filterDesc);
        contextBar.append("</div>");

        // Right side: custom element and quick actions
        contextBar.append("<div class='quick-actions'>");
        if (customElement != null && !customElement.isEmpty()) {
            contextBar.append(customElement);
        }

        // Add action buttons
        for (Action action : actions) {
            appendActionButton(contextBar, action);
        }

        contextBar.append("</div>");
        contextBar.append("</div>");
        return contextBar.toString();
    }

    /**
     * Append an action button to the context bar
     */
    private static void appendActionButton(StringBuilder sb, Action action) {
        sb.append("<a href='").append(action.url).append("'>").append(action.label).append("</a>");
    }

    /**
     * Get a human-readable description of the active filter
     * @param exchange The HTTP exchange
     * @return Description of active filters (or empty string if none)
     */
    public static String getActiveFilterDescription(HttpExchange exchange) {
        Map<String, String> params = getURLParameters(exchange);
        StringBuilder desc = new StringBuilder();

        if (params.containsKey("pattern")) {
            desc.append("<code class='filter-pattern'>").append(escapeHtml(params.get("pattern"))).append("</code>");
        }

        if (params.containsKey("instr")) {
            if (!desc.isEmpty()) {
                desc.append(" and ");
            }
            desc.append("instrumentator <code class='filter-pattern'>").append(escapeHtml(params.get("instr"))).append("</code>");
        }

        if (params.containsKey("mode")) {
            if (!desc.isEmpty()) {
                desc.append(" ");
            }
            desc.append("(mode: <code class='filter-pattern'>").append(escapeHtml(params.get("mode"))).append("</code>)");
        }

        return desc.toString();
    }

    public record Action(String label, String url) {}

    /**
     * Build quick action links for context bar
     * @param currentPath Current request path
     * @param params Current URL parameters
     * @return List of action
     */
    public static List<Action> buildQuickActions(String currentPath, Map<String, String> params) {
        List<Action> quickActions = new ArrayList<>();

        // Build clear filter link
        if (params.containsKey("pattern")) {
            quickActions.add(new Action("Clear Filter", currentPath.split("\\?")[0]));
        }

        // Build view all link (context-dependent)
        if (currentPath.startsWith("/classes") && !currentPath.startsWith("/all/")) {
            quickActions.add(new Action("View All Classes", "/all/classes"));
        }

        // Build raw output link
        quickActions.add(new Action("Raw Output", buildUrlWithParam(currentPath, params, "output", "raw")));

        return quickActions;
    }

    /**
     * Build a URL with an additional or modified parameter
     */
    private static String buildUrlWithParam(String currentPath, Map<String, String> params, String paramKey, String paramValue) {
        Map<String, String> newParams = new HashMap<>(params);
        newParams.put(paramKey, paramValue);
        String basePath = currentPath.split("\\?")[0];
        String queryString = newParams.entrySet().stream()
            .map(e -> e.getKey() + "=" + e.getValue())
            .collect(joining("&"));
        return basePath + (queryString.isEmpty() ? "" : "?" + queryString);
    }

    /**
     * Parse URL parameters from the exchange
     */
    private static Map<String, String> getURLParameters(HttpExchange exchange) {
        String query = exchange.getRequestURI().getQuery();
        if (query == null) {
            return Map.of();
        }
        return Arrays.stream(query.split("&"))
                .map(s -> s.split("=", 2))
                .filter(a -> a.length == 2)
                .collect(Collectors.toMap(a -> a[0], a -> a[1]));
    }

    /**
     * Escape HTML special characters
     */
    private static String escapeHtml(String text) {
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;")
                   .replace("'", "&#39;");
    }

    /**
     * Convert a boolean active state to an HTML class attribute
     * @param isActive whether the navigation item is active
     * @return " class='active'" if active, empty string otherwise
     */
    private static String getActiveClass(boolean isActive) {
        return isActive ? " class='active'" : "";
    }

    /**
     * Check if the current path matches any of the provided exact or prefix paths
     */
    private static boolean isActive(String currentPath, String... paths) {
        for (String path : paths) {
            if (currentPath.equals(path) || currentPath.startsWith(path)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Determine if the Instrumentators nav item should be active.
     * Active for paths starting with /instrumentators or containing /instrumentator
     */
    private static boolean isInstrumentatorsActive(String currentPath) {
        return currentPath.startsWith("/instrumentators") || currentPath.contains("/instrumentator");
    }

    /**
     * Determine if the Classes nav item should be active.
     * Active for /classes, /diff/class, /full-diff/class, /decompile, but NOT for /all/
     */
    private static boolean isClassesActive(String currentPath) {
        if (currentPath.startsWith("/all/")) {
            return false;
        }
        return currentPath.startsWith("/classes")
            || currentPath.startsWith("/diff/class")
            || currentPath.startsWith("/full-diff/class")
            || currentPath.startsWith("/decompile");
    }
}