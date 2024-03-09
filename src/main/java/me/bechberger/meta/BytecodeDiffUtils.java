package me.bechberger.meta;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.java.decompiler.main.decompiler.ConsoleDecompiler;

/** Utility class for computing bytecode diffs using the decompiled source code and gnu-diff */
public class BytecodeDiffUtils {

  private static void deleteFolder(@Nullable Path folder) {
    if (folder == null) {
      return;
    }
    try {
      Files.walk(folder).sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
    } catch (IOException e) {
    }
  }

  enum DiffSourceMode {
    JAVA(".java", "java"),
    VERBOSE_BYTECODE(".bytecode", "javap"),
    ULTRA_VERBOSE_BYTECODE(".bytecode", "javap-verbose");
    final String suffix;
    final String name;
    DiffSourceMode(String suffix, String name) {
      this.suffix = suffix;
      this.name = name;
    }
  }

  static String diff(Map<Class<?>, BytecodeDiff> diffPerClass, DiffSourceMode mode, boolean showAll) {
    var oldSourcePerClass =
        decompileClasses(
            diffPerClass.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().old())), mode);
    var newSourcePerClass =
        decompileClasses(
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
      deleteFolder(tmp);
    }
  }

  static Map<Class<?>, String> decompileClasses(Map<Class<?>, byte[]> bytecodePerClass, DiffSourceMode mode) {
    return switch (mode) {
      case JAVA -> decompileClassesToJava(bytecodePerClass);
      case VERBOSE_BYTECODE -> decompileClassesToVerboseBytecode(bytecodePerClass, false);
      case ULTRA_VERBOSE_BYTECODE -> decompileClassesToVerboseBytecode(bytecodePerClass, true);
    };
  }

  static Map<Class<?>, String> decompileClassesToJava(Map<Class<?>, byte[]> bytecodePerClass) {
    Map<String, List<Class<?>>> classesPerSimpleName =
        bytecodePerClass.keySet().stream().collect(Collectors.groupingBy(Class::getSimpleName));
    int maxIndex = classesPerSimpleName.values().stream().mapToInt(List::size).max().orElse(0);
    Set<String> toProcess = new HashSet<>(classesPerSimpleName.keySet());
    Map<Class<?>, String> result = new HashMap<>();
    for (int i = 0; i < maxIndex; i++) {
      Map<Class<?>, byte[]> bytecodePerClassForPackage = new HashMap<>();
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

  private static Map<Class<?>, String> decompileClassesWithoutClassNameDuplicates(
      Map<Class<?>, byte[]> bytecodePerClass) {
    var oldOut = System.out;
    Path tmpDir = null;
    try {
      System.setOut(
          new java.io.PrintStream(
              new java.io.OutputStream() {
                @Override
                public void write(int b) {}
              }));
      tmpDir = Files.createTempDirectory("classviewer");
      Map<Class<?>, String> result = new HashMap<>();
      List<Path> classPaths = new ArrayList<>();
      for (Class<?> c : bytecodePerClass.keySet()) {
        var packageName = c.getPackageName();
        var path = packageName.isEmpty() ? tmpDir : tmpDir.resolve(packageName.replace(".", "/"));
        var classPath = path.resolve(c.getSimpleName() + ".class");
        Files.createDirectories(path);
        Files.write(classPath, bytecodePerClass.get(c));
        classPaths.add(classPath);
      }
      String[] args = new String[classPaths.size() + 2];
      args[0] = "-jrt=1"; // use current runtime
      for (int i = 0; i < classPaths.size(); i++) {
        args[i + 1] = classPaths.get(i).toString();
      }
      args[bytecodePerClass.size() + 1] = tmpDir.toString();
      ConsoleDecompiler.main(args);
      for (Class<?> c : bytecodePerClass.keySet()) {
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

  static Map<Class<?>, String> decompileClassesToVerboseBytecode(Map<Class<?>, byte[]> bytecodePerClass, boolean ultraVerbose) {
    Path tmpDir = null;
    try {
      tmpDir = Files.createTempDirectory("classviewer");
      Map<Class<?>, String> result = new HashMap<>();
      Map<String, Class<?>> classNameToClass = new HashMap<>();
      int i = 0;
      List<String> args = new ArrayList<>();
      args.add("javap");
      args.add("-cp");
      args.add(".");
      args.add("-c"); // show bytecode
      if (ultraVerbose) {
        args.add("-v");
      }
      // store class bytecode in temporary files
      for (Map.Entry<Class<?>, byte[]> entry : bytecodePerClass.entrySet()) {
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
      while (lines.hasNext()) {
        var line = lastLine.isEmpty() ? lines.next() : lastLine;
        if (line.startsWith("Warning: File ")) {
          continue;
        }
        if (line.startsWith("Compiled from ")) {
          line = lines.next();
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
            if (line.startsWith("Compiled from ")) {
              lastLine = line;
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
