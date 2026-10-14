package me.bechberger.meta;

import me.bechberger.femtocli.FemtoCli;
import me.bechberger.femtocli.annotations.Command;
import me.bechberger.femtocli.annotations.Option;
import me.bechberger.meta.runtime.Options;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.List;
import java.util.jar.JarFile;
import java.util.concurrent.Callable;

/**
 * Agent entry point.
 */
public class Main {

    @Command(
            name = "meta-agent",
            description = "Bytecode instrumentation inspector — wraps ClassFileTransformers and serves live diffs.",
            version = "0.0.10",
            mixinStandardHelpOptions = true
    )
    static class AgentArgs implements Callable<Integer> {

        @Option(names = "--server", description = "Start the HTTP server")
        boolean server = false;

        @Option(names = "--port", description = "HTTP server port (default: ${DEFAULT-VALUE})", defaultValue = "7071")
        int port = 7071;

        @Option(names = "--keepalive", description = "Keep the JVM alive after the program exits so the server stays reachable; implies server")
        boolean keepalive = false;

        @Option(names = "--disable-native", description = "Don't check for files created by the native wrapper agent")
        boolean disableNative = false;

        @Option(names = "--cb", description = "Callback class name (implements InstrumentationCallback); repeat for multiple")
        List<String> callbackClasses = List.of();

        @Override
        public Integer call() {
            return 0;
        }

        Options toOptions() {
            Options o = new Options();
            o.server = server;
            o.port = port;
            o.keepalive = keepalive;
            o._native = !disableNative;
            o.callbackClasses = new HashSet<>(callbackClasses);
            return o;
        }
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

        AgentArgs parsed = new AgentArgs();
        int exitCode = FemtoCli.builder()
                .alertOnMixedStyleInAgent(true)
                .runAgent(parsed, agentArgs != null ? agentArgs : "");

        if (exitCode != 0) {
            return; // --help or --version was printed
        }

        Options options = parsed.toOptions();
        if (options._native) {
            NativeWrapperLoop.startThread();
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
