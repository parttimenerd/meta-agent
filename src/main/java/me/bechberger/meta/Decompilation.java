package me.bechberger.meta;

import me.bechberger.meta.runtime.Klass;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.java.decompiler.main.decompiler.ConsoleDecompiler;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class Decompilation {

    public static void deleteFolder(@Nullable Path folder) {
        if (folder == null) {
            return;
        }
        try {
            try (var files = Files.walk(folder)) {
                files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
            }
        } catch (IOException e) {
        }
    }

    public static Map<Klass, String> decompileClasses(Map<Klass, byte[]> bytecodePerClass, DiffSourceMode mode) {
        return switch (mode) {
            case JAVA -> decompileClassesToJava(bytecodePerClass);
            case VERBOSE_BYTECODE -> decompileClassesToVerboseBytecode(bytecodePerClass, false);
            case ULTRA_VERBOSE_BYTECODE -> decompileClassesToVerboseBytecode(bytecodePerClass, true);
            case AI_JAVA -> decompileClassesToJavaWithAI(bytecodePerClass);
        };
    }

    public static Map<Klass, String> decompileClassesToJava(Map<Klass, byte[]> bytecodePerClass) {
        Map<String, List<Klass>> classesPerSimpleName =
                bytecodePerClass.keySet().stream().collect(Collectors.groupingBy(Klass::getSimpleName));
        int maxIndex = classesPerSimpleName.values().stream().mapToInt(List::size).max().orElse(0);
        Set<String> toProcess = new HashSet<>(classesPerSimpleName.keySet());
        Map<Klass, String> result = new HashMap<>();
        for (int i = 0; i < maxIndex; i++) {
            Map<Klass, byte[]> bytecodePerClassForPackage = new HashMap<>();
            Set<String> removeFromProcess = new HashSet<>();
            for (String className : toProcess) {
                var classes = classesPerSimpleName.get(className);
                if (i < classes.size()) {
                    bytecodePerClassForPackage.put(classes.get(i), bytecodePerClass.get(classes.get(i)));
                }
                if (i >= classes.size() - 1) {
                    removeFromProcess.add(className);
                }
                result.putAll(decompileClassesWithoutClassNameDuplicates(bytecodePerClassForPackage));
            }
            toProcess.removeAll(removeFromProcess);
        }
        return result;
    }

    /**
     * Decompiles the given classes to Java using the Fernflower decompiler and then improves the decompilation using the AI model {@value AI_MODEL}
     * <p/>
     * Might take some time as it runs locally
     * @param bytecodePerClass the classes to decompile
     * @return a map from the classes to their decompiled source code
     */
    public static Map<Klass, String> decompileClassesToJavaWithAI(Map<Klass, byte[]> bytecodePerClass) {
        return improveDecompilationWithAI(decompileClassesToJava(bytecodePerClass));
    }

    public static final String AI_MODEL = "deepseek-r1:14b";
    private static final String AI_PROMPT = "please improve the following Java decompilation, only give me the decompiled code and give variables proper names, without explanations. Put the Java code in markdown code block:\n";

    private static Map<Klass, String> improveDecompilationWithAI(Map<Klass, String> decompiledClasses) {
        // essientially call `ollama run deepseek-r1:14b < input.txt > out.txt` for each klass and record the output
        // then return the output
        return decompiledClasses.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, e -> {
            try {
                var process = new ProcessBuilder("ollama", "run", AI_MODEL)
                        .redirectInput(ProcessBuilder.Redirect.PIPE)
                        .redirectOutput(ProcessBuilder.Redirect.PIPE)
                        .start();
                process.getOutputStream().write((AI_PROMPT + e.getValue()).getBytes());
                process.getOutputStream().close();
                System.out.println("Running ollama for " + e.getKey().getName());
                // read the lines, print them and return them
                var in = new InputStreamReader(new BufferedInputStream(process.getInputStream()));
                // read lines from the input stream
                var lines = new ArrayList<String>();
                var reader = new BufferedReader(in);
                String line;
                boolean hasJava = false;
                while ((line = reader.readLine()) != null) {
                    System.out.println(line);
                    if (hasJava) {
                        lines.add(line);
                    }
                    if (line.startsWith("```java")) {
                        hasJava = true;
                    } else if (line.startsWith("```")) {
                        hasJava = false;
                    }
                }
                return String.join("\n", lines);
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
        }));
    }

    private static Map<Klass, String> decompileClassesWithoutClassNameDuplicates(
            Map<Klass, byte[]> bytecodePerClass) {
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
            Map<Klass, String> result = new HashMap<>();
            List<Path> classPaths = new ArrayList<>();
            for (Klass c : bytecodePerClass.keySet()) {
                var packageName = c.getPackageName();
                var path = packageName.isEmpty() ? tmpDir : tmpDir.resolve(packageName.replace(".", "/"));
                var classPath = path.resolve(c.getSimpleName() + ".class");
                Files.createDirectories(path);
                Files.write(classPath, bytecodePerClass.get(c));
                classPaths.add(classPath);
            }
            String[] addArgs = "-jrt=1 -rbr=0 -rsy=0".split(" ");
            String[] args = new String[classPaths.size() + 1 + addArgs.length];
            System.arraycopy(addArgs, 0, args, 0, addArgs.length);
            for (int i = 0; i < classPaths.size(); i++) {
                args[addArgs.length + i] = classPaths.get(i).toString();
            }
            args[args.length - 1] = tmpDir.toString();
            ConsoleDecompiler.main(args);
            for (Klass c : bytecodePerClass.keySet()) {
                var path = tmpDir.resolve(c.getSimpleName() + ".java");
                if (Files.exists(path)) {
                    result.put(c, Files.readString(path));
                }
            }
            return result;
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            System.setOut(oldOut);
            deleteFolder(tmpDir);
        }
    }

    public static Map<Klass, String> decompileClassesToVerboseBytecode(Map<Klass, byte[]> bytecodePerClass, boolean ultraVerbose) {
        Path tmpDir = null;
        try {
            tmpDir = Files.createTempDirectory("classviewer");
            Map<Klass, String> result = new HashMap<>();
            Map<String, Klass> classNameToClass = new HashMap<>();
            int i = 0;
            List<String> args = new ArrayList<>();
            args.add("javap");
            args.add("-cp");
            args.add(".");
            args.add("-p");
            args.add("-c"); // show bytecode
            if (ultraVerbose) {
                args.add("-v");
            }
            // store class bytecode in temporary files
            for (Map.Entry<Klass, byte[]> entry : bytecodePerClass.entrySet()) {
                String synthClassName = "class" + i++;
                Path classFile = tmpDir.resolve(synthClassName + ".class");
                Files.write(classFile, entry.getValue());
                args.add(synthClassName);
                classNameToClass.put(entry.getKey().getName(), entry.getKey());
            }
            // run javap with the given arguments in the temporary directory and record the output
            Process p = new ProcessBuilder(args).directory(tmpDir.toFile()).start();
            String out = new String(p.getInputStream().readAllBytes());
            // parse the output and store it in the result map
            // ignore lines that start with "Warning: File "
            // the output for each class starts with the line 'Compiled from "SynthName.java"'
            var lines = out.lines().iterator();
            var lastLine = "";
            Pattern startPattern = Pattern.compile("(^|([a-z]+ )+)(class|interface|enum|record) [^()]+\\{");
            if (ultraVerbose) {
                startPattern = Pattern.compile("^Classfile .*" + tmpDir + "/class[0-9]+.class");
            }
            while (lines.hasNext()) {
                var line = lastLine.isEmpty() ? lines.next() : lastLine;
                if (line.startsWith("Warning: File ")) {
                    continue;
                }
                if (startPattern.matcher(line).matches()) {
                    if (ultraVerbose) {
                        while ((line = lines.next()).startsWith(" "));
                    }
                    var matcher = Pattern.compile("(^|.* )(class|interface|enum|record) ([^ <]*).*").matcher(line);
                    var className = "";
                    if (matcher.find()) {
                        className = matcher.group(3);
                    } else {
                        throw new RuntimeException("Could not parse class name from line: " + line);
                    }
                    var classOutput = new StringBuilder();
                    classOutput.append(line).append("\n");
                    classOutput.append(line).append("\n");
                    while (lines.hasNext()) {
                        line = lines.next();
                        if (startPattern.matcher(line).matches()) {
                            lastLine = line.trim();
                            break;
                        }
                        classOutput.append(line).append("\n");
                    }
                    result.put(classNameToClass.get(className), classOutput.toString());
                }
            }
            return result;
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            deleteFolder(tmpDir);
        }
    }
}
