import com.dylibso.chicory.build.time.compiler.Config;
import com.dylibso.chicory.build.time.compiler.Generator;
import com.dylibso.chicory.compiler.InterpreterFallback;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.spi.ToolProvider;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/**
 * Build-time: compile afp.query.wasm → Chicory AOT JVM bytecode jar.
 *
 * Args: &lt;wasmPath&gt; &lt;outJar&gt; &lt;workDir&gt; &lt;runtimeClasspath&gt;
 * runtimeClasspath is a File.pathSeparator-separated list used to javac the generated module facade.
 */
public final class CompileAfpAot {
    private static final String MODULE_NAME = "app.hypochlorite.audio.wasm.AfpQueryModule";

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            System.err.println("usage: CompileAfpAot <wasm> <outJar> <workDir> <runtimeClasspath>");
            System.exit(2);
        }
        Path wasm = Path.of(args[0]);
        Path outJar = Path.of(args[1]);
        Path work = Path.of(args[2]);
        String runtimeCp = args[3];

        wipe(work);
        Files.createDirectories(work);
        Path classes = work.resolve("classes");
        Path sources = work.resolve("sources");
        Path wasmOut = work.resolve("wasm-res");
        Files.createDirectories(classes);
        Files.createDirectories(sources);
        Files.createDirectories(wasmOut);

        if (!Files.isRegularFile(wasm) || Files.size(wasm) < 100_000) {
            System.out.println("CompileAfpAot: no usable wasm at " + wasm + " — writing stub module");
            writeStub(sources);
            javac(sources, classes, runtimeCp);
            jar(classes, outJar);
            return;
        }

        Config config = Config.builder()
                .withWasmFile(wasm)
                .withName(MODULE_NAME)
                .withTargetClassFolder(classes)
                .withTargetSourceFolder(sources)
                .withTargetWasmFolder(wasmOut)
                // Prefer FAIL awareness: WARN logs oversized funcs then still ships AOT for the rest.
                .withInterpreterFallback(InterpreterFallback.WARN)
                .build();
        Generator generator = new Generator(config);
        Set<Integer> interpreted = generator.generateResources();
        if (!interpreted.isEmpty()) {
            System.out.println("CompileAfpAot: interpreter fallback for functions " + interpreted);
        } else {
            System.out.println("CompileAfpAot: all functions AOT-compiled");
        }
        generator.generateSources();
        generator.generateMetaWasm(interpreted);

        // .meta must sit next to AfpQueryModule.class (getResourceAsStream("AfpQueryModule.meta"))
        Path metaSrc = wasmOut.resolve("app/hypochlorite/audio/wasm/AfpQueryModule.meta");
        Path metaDst = classes.resolve("app/hypochlorite/audio/wasm/AfpQueryModule.meta");
        Files.createDirectories(metaDst.getParent());
        Files.copy(metaSrc, metaDst, StandardCopyOption.REPLACE_EXISTING);

        // Machine *.class already in classes/; facade references them, so put classes on javac cp.
        javac(sources, classes, runtimeCp + File.pathSeparator + classes);
        jar(classes, outJar);
        System.out.println("CompileAfpAot: wrote " + outJar + " (" + Files.size(outJar) + " bytes)");
    }

    private static void writeStub(Path sources) throws IOException {
        Path file = sources.resolve("app/hypochlorite/audio/wasm/AfpQueryModule.java");
        Files.createDirectories(file.getParent());
        String src = ""
                + "package app.hypochlorite.audio.wasm;\n"
                + "\n"
                + "import com.dylibso.chicory.runtime.CompiledModule;\n"
                + "import com.dylibso.chicory.runtime.Instance;\n"
                + "import com.dylibso.chicory.runtime.Machine;\n"
                + "import com.dylibso.chicory.wasm.WasmModule;\n"
                + "import java.util.function.Function;\n"
                + "\n"
                + "/** Stub when afp.query.wasm was missing at build time. */\n"
                + "public final class AfpQueryModule implements CompiledModule {\n"
                + "    public AfpQueryModule() {}\n"
                + "    public static Machine create(Instance instance) {\n"
                + "        throw new IllegalStateException(\"afp.query.wasm missing at build; AOT stub\");\n"
                + "    }\n"
                + "    public static WasmModule load() {\n"
                + "        throw new IllegalStateException(\"afp.query.wasm missing at build; AOT stub\");\n"
                + "    }\n"
                + "    public Function<Instance, Machine> machineFactory() { return AfpQueryModule::create; }\n"
                + "    public WasmModule wasmModule() { return load(); }\n"
                + "}\n";
        Files.writeString(file, src, StandardCharsets.UTF_8);
    }

    private static void javac(Path sources, Path classes, String runtimeCp) throws IOException {
        List<String> javaFiles;
        try (Stream<Path> walk = Files.walk(sources)) {
            javaFiles = walk.filter(p -> p.toString().endsWith(".java")).map(Path::toString).collect(Collectors.toList());
        }
        if (javaFiles.isEmpty()) throw new IllegalStateException("no generated sources under " + sources);
        ToolProvider javac = ToolProvider.findFirst("javac")
                .orElseThrow(() -> new IllegalStateException("javac not found"));
        List<String> args = new ArrayList<>();
        args.add("-encoding");
        args.add("UTF-8");
        args.add("-source");
        args.add("17");
        args.add("-target");
        args.add("17");
        args.add("-cp");
        args.add(runtimeCp);
        args.add("-d");
        args.add(classes.toString());
        args.addAll(javaFiles);
        int code = javac.run(System.out, System.err, args.toArray(new String[0]));
        if (code != 0) throw new IllegalStateException("javac failed with exit " + code);
    }

    private static void jar(Path classes, Path outJar) throws IOException {
        Files.createDirectories(outJar.getParent());
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(outJar));
                Stream<Path> walk = Files.walk(classes)) {
            List<Path> files = walk.filter(Files::isRegularFile).sorted().collect(Collectors.toList());
            for (Path file : files) {
                String name = classes.relativize(file).toString().replace('\\', '/');
                jos.putNextEntry(new JarEntry(name));
                Files.copy(file, jos);
                jos.closeEntry();
            }
        }
    }

    private static void wipe(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }
}
