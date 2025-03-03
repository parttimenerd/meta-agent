package me.bechberger.meta.runtime;

import org.jetbrains.annotations.Nullable;
import org.jetbrains.java.decompiler.main.decompiler.ConsoleDecompiler;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class SimpleDecompilation {

    public static void deleteFolder(@Nullable Path folder) {
        if (folder == null) {
            return;
        }
        try {
            Files.walk(folder).sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
        } catch (IOException e) {
        }
    }

    public static String decompileClass(
            ClassArtifact artifact) {
        var oldOut = System.out;
        Path tmpDir = null;
        try {
            System.setOut(
                    new java.io.PrintStream(
                            new java.io.OutputStream() {
                                @Override
                                public void write(int b) {
                                }
                            }));
            tmpDir = Files.createTempDirectory("classviewer");
            var packageName = artifact.klass().getPackageName();
            var path = packageName.isEmpty() ? tmpDir : tmpDir.resolve(packageName.replace(".", "/"));
            Path classPath = path.resolve(artifact.klass().getSimpleName() + ".class");
            Files.createDirectories(path);
            Files.write(classPath, artifact.bytecode());
            String[] addArgs = "-jrt=1 -rbr=0 -rsy=0".split(" ");
            String[] args = new String[2 + addArgs.length];
            System.arraycopy(addArgs, 0, args, 0, addArgs.length);
            args[addArgs.length] = classPath.toString();
            args[args.length - 1] = tmpDir.toString();
            ConsoleDecompiler.main(args);
            return Files.readString(tmpDir.resolve(artifact.klass().getSimpleName() + ".java"));
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            System.setOut(oldOut);
            deleteFolder(tmpDir);
        }
    }
}
