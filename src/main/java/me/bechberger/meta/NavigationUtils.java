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
     * @param port The server port
     * @return HTML for the navigation header
     */
    public static String getNavigationHeader(String currentPath, int port) {
        // Determine which nav link is active based on current path
        String homeClass = currentPath.equals("/") || currentPath.equals("/help") ? " class='active'" : "";
        String instrumentatorsClass = currentPath.startsWith("/instrumentators") || currentPath.contains("/instrumentator") ? " class='active'" : "";
        String classesClass = (currentPath.startsWith("/classes") || currentPath.startsWith("/diff/class") ||
                               currentPath.startsWith("/full-diff/class") || currentPath.startsWith("/decompile")) &&
                              !currentPath.startsWith("/all/") ? " class='active'" : "";
        String allClassesClass = currentPath.startsWith("/all/") ? " class='active'" : "";

        return """
            <nav class="nav-header">
                <div class="nav-left">
                    <span class="title">Meta-Agent</span>
                    <span class="subtitle">Bytecode Instrumentation Inspector</span>
                </div>
                <div class="nav-links">
                    <a href="/"$HOME_CLASS$>Home</a>
                    <a href="/instrumentators"$INSTRS_CLASS$>Instrumentators</a>
                    <a href="/classes"$CLASSES_CLASS$>Classes</a>
                    <a href="/all/classes"$ALL_CLASSES_CLASS$>All Classes</a>
                </div>
                <div class="nav-right">
                    <span class="meta-info">
                        <a href="https://github.com/parttimenerd/meta-agent" title="GitHub Repository">GitHub</a>
                    </span>
                </div>
            </nav>
            """
            .replace("$HOME_CLASS$", homeClass)
            .replace("$INSTRS_CLASS$", instrumentatorsClass)
            .replace("$CLASSES_CLASS$", classesClass)
            .replace("$ALL_CLASSES_CLASS$", allClassesClass)
            .replace("$PORT$", String.valueOf(port));
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
        breadcrumbs.append("<a href='/'>Home</a>");

        // Parse the path to build breadcrumb trail
        if (path.startsWith("/instrumentators") || path.contains("/instrumentator")) {
            breadcrumbs.append("<span class='separator'>›</span>");
            breadcrumbs.append("<a href='/instrumentators'>Instrumentators</a>");

            if (params.containsKey("pattern")) {
                breadcrumbs.append("<span class='separator'>›</span>");
                breadcrumbs.append("<span class='current'>").append(escapeHtml(params.get("pattern"))).append("</span>");
            }

            if (path.contains("/diff/")) {
                breadcrumbs.append("<span class='separator'>›</span>");
                breadcrumbs.append("<span class='current'>Diffs</span>");
            }
        } else if (path.startsWith("/classes") || path.startsWith("/all/classes")) {
            breadcrumbs.append("<span class='separator'>›</span>");
            boolean all = path.startsWith("/all/");
            String classesLink = all ? "/all/classes" : "/classes";
            String classesLabel = all ? "All Classes" : "Classes";
            breadcrumbs.append("<a href='").append(classesLink).append("'>").append(classesLabel).append("</a>");

            if (params.containsKey("pattern")) {
                breadcrumbs.append("<span class='separator'>›</span>");
                breadcrumbs.append("<span class='current'>").append(escapeHtml(params.get("pattern"))).append("</span>");
            }
        } else if (path.contains("/diff/class") || path.contains("/full-diff/class")) {
            breadcrumbs.append("<span class='separator'>›</span>");
            breadcrumbs.append("<a href='/classes'>Classes</a>");

            if (params.containsKey("pattern")) {
                breadcrumbs.append("<span class='separator'>›</span>");
                breadcrumbs.append("<span class='current'>").append(escapeHtml(params.get("pattern"))).append("</span>");
            }

            breadcrumbs.append("<span class='separator'>›</span>");
            breadcrumbs.append("<span class='current'>Diffs</span>");
        } else if (path.startsWith("/decompile") || path.startsWith("/all/decompile")) {
            breadcrumbs.append("<span class='separator'>›</span>");
            breadcrumbs.append("<span class='current'>Decompile</span>");

            if (params.containsKey("pattern")) {
                breadcrumbs.append("<span class='separator'>›</span>");
                breadcrumbs.append("<span class='current'>").append(escapeHtml(params.get("pattern"))).append("</span>");
            }
        }

        // Add any additional context
        for (String context : additionalContext) {
            if (context != null && !context.isEmpty()) {
                breadcrumbs.append("<span class='separator'>›</span>");
                breadcrumbs.append("<span class='context'>").append(escapeHtml(context)).append("</span>");
            }
        }

        breadcrumbs.append("</div>");
        return breadcrumbs.toString();
    }

    /**
     * Get context bar showing current filter and quick actions
     * @param exchange The HTTP exchange
     * @param resultCount Number of results (use -1 to skip displaying count)
     * @param actions Quick action buttons (as key-value pairs: "label:url")
     * @return HTML for context bar
     */
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
        StringBuilder contextBar = new StringBuilder();
        contextBar.append("<div class='context-bar'>");

        // Left side: filter info
        contextBar.append("<div class='filter-info'>");

        String filterDesc = getActiveFilterDescription(exchange);
        if (resultCount >= 0) {
            contextBar.append("Showing <strong>").append(resultCount).append("</strong> ");
            contextBar.append(resultCount == 1 ? "result" : "results");
            if (!filterDesc.isEmpty()) {
                contextBar.append(" matching ");
            }
        }

        if (!filterDesc.isEmpty()) {
            contextBar.append(filterDesc);
        }

        contextBar.append("</div>");

        // Right side: custom element and quick actions
        contextBar.append("<div class='quick-actions'>");

        // Add custom element if provided
        if (customElement != null && !customElement.isEmpty()) {
            contextBar.append(customElement);
        }

      // Add action buttons
      for (Action action : actions) {
        contextBar.append("<a href='")
            .append(action.url)
            .append("'>")
            .append(action.label)
            .append("</a>");
      }

        contextBar.append("</div>");
        contextBar.append("</div>");
        return contextBar.toString();
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
        Map<String, String> rawParams = new HashMap<>(params);
        rawParams.put("output", "raw");
        String rawUrl = currentPath.split("\\?")[0] + "?" + rawParams.entrySet().stream()
            .map(e -> e.getKey() + "=" + e.getValue())
            .collect(joining("&"));
        quickActions.add(new Action("Raw Output", rawUrl));

        return quickActions;
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
}
