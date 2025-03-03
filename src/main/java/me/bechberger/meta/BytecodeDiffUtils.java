package me.bechberger.meta;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Utility class for computing bytecode diffs using the decompiled source code and gnu-diff
 */
public class BytecodeDiffUtils {

    public static String diff(Map<Class<?>, SimpleBytecodeDiff> diffPerClass, DiffSourceMode mode, boolean showAll) {
        var oldSourcePerClass =
                Decompilation.decompileClasses(
                        diffPerClass.entrySet().stream()
                                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().old())), mode);
        var newSourcePerClass =
                Decompilation.decompileClasses(
                        diffPerClass.entrySet().stream()
                                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().current())), mode);
        Path tmp = null;
        try {
            tmp = Files.createTempDirectory("bytecode-diff");
            var oldFolder = tmp.resolve("old");
            var newFolder = tmp.resolve("new");
            Files.createDirectories(oldFolder);
            Files.createDirectories(newFolder);
            for (var entry : diffPerClass.entrySet()) {
                var oldFile = oldFolder.resolve(entry.getKey().getName() + mode.suffix);
                var newFile = newFolder.resolve(entry.getKey().getName() + mode.suffix);
                Files.writeString(oldFile, oldSourcePerClass.get(entry.getKey()));
                Files.writeString(newFile, newSourcePerClass.get(entry.getKey()));
            }
            List<String> args = new ArrayList<>();
            args.add("diff");
            if (showAll) {
                args.add("-U100000");
            } else {
                args.add("-U10");
            }
            args.add(tmp.relativize(oldFolder).toString());
            args.add(tmp.relativize(newFolder).toString());
            Process p = new ProcessBuilder(args).directory(tmp.toFile()).start();
            // capture the output of the process and return it
            String out = new String(p.getInputStream().readAllBytes());
            // remove all time stamps from the output that come after the file names, but keep the file
            // names
            return out.lines()
                    .map(
                            l ->
                                    (l.startsWith("---") || l.startsWith("+++"))
                                            ? l.replaceAll(
                                            "((---|\\+\\+\\+) .*)(\\t\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2})",
                                            "$1")
                                            : l)
                    .collect(Collectors.joining("\n"));
        } catch (IOException e) {
            return "Error: " + e.getMessage();
        } finally {
            Decompilation.deleteFolder(tmp);
        }
    }
}
