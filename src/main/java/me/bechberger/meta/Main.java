package me.bechberger.meta;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.jar.JarFile;

/**
 * Agent entry
 */
public class Main {

    private static Instrumentation inst;

    static class Options {
        boolean server = false;
        int port = 7071;
        boolean help = false;
        Set<String> callbackClasses = new HashSet<>();
    }

    record Option(String name, String description, boolean hasArgument, BiConsumer<Options, String> process) {
    }

    static final List<Option> OPTIONS = List.of(
            new Option("help", "Show this help", false, (o, a) -> o.help = true),
            new Option("server", "Start the server at the passed port (default 7071)", false, (o, a) -> o.server = true),
            new Option("port", "Port to start the server on, default 7071", true, (o, a) -> o.port = Integer.parseInt(a)),
            new Option("cb", "Callback class names, classes have to implement the InstrumentationCallback interface", true, (o, a) -> {
                o.callbackClasses.add(a);
            }));

    private static String getHelp() {
        StringBuilder builder = new StringBuilder("Usage: java -javaagent:meta-agent.jar[=options] -jar your.jar\n");
        for (Option option : OPTIONS) {
            builder.append("\n").append(option.name).append(": ").append(option.description);
        }
        return builder.toString();
    }

    private static Options parseOptionsFromArgs(String args) {
        Options options = new Options();
        if (args == null) {
            return options;
        }
        String[] parts = args.split(",");
        for (String part : parts) {
            String[] keyValue = part.split("=");
            String key = keyValue[0];
            String value = keyValue.length > 1 ? keyValue[1] : null;
            Option option = OPTIONS.stream().filter(o -> o.name.equals(key)).findFirst().orElse(null);
            if (option == null) {
                System.err.println("Unknown option: " + key);
                continue;
            }
            if (option.hasArgument && value == null) {
                System.err.println("Option " + key + " requires an argument\n" + getHelp());
                continue;
            }
            option.process.accept(options, value);
        }
        return options;
    }

    public static void agentmain(String args, Instrumentation inst) {
        premain(args, inst);
    }

    public static void premain(String agentArgs, Instrumentation inst) {
        if (agentArgs == null || agentArgs.isEmpty()) {
            System.out.println(getHelp());
            return;
        }
        try {
            inst.appendToBootstrapClassLoaderSearch(new JarFile(getExtractedJARPath().toFile()));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        Main.inst = inst;
        Options options = parseOptionsFromArgs(agentArgs);
        if (options.help) {
            System.out.println(getHelp());
            return;
        }
        MainLoop.run(options, inst);
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
}
