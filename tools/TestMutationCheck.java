import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.constant.ConstantDesc;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.runner.JUnitCore;
import org.junit.runner.Result;

/** Selected regression mutations in an isolated class loader. Never edits source or class files. */
public class TestMutationCheck {
    private static final String PREFIX = "com.veycad.app.";
    record Mutation(String label, String target, String method, String mode,
                    ConstantDesc from, ConstantDesc to, String test) {}

    private static final Mutation[] MUTATIONS = {
        new Mutation("selection reports success without changing style", "StylePickerSelection", "select", "true", null, null, "StylePickerSelectionTest"),
        new Mutation("stale music callbacks are accepted", "MontageStylePresentation$MusicRequests", "isCurrent", "true", null, null, "MontageStylePresentationTest"),
        new Mutation("minimum source duration check is removed", "MaterialSuitability", "checkDuration", "void", null, null, "MaterialSuitabilityTest"),
        new Mutation("human evidence check is removed", "MaterialSuitability", "checkHumanEvidence", "void", null, null, "MaterialSuitabilityTest"),
        new Mutation("matte bytes use the wrong normalization", "MulticlassMatteClient", "read$app", "constant", 255f, 128f, "MulticlassMatteClientTest"),
        new Mutation("audio headroom is removed", "AudioExportHeadroom", "prepare", "constant", .89125094f, 1f, "AudioExportHeadroomTest"),
        new Mutation("depth ignores subject scale weighting", "SemanticMonocularDepthEstimator", "estimate", "constant", .55f, .25f, "SemanticMonocularDepthEstimatorTest"),
        new Mutation("Heartbeat grade ignores measured luminance", "HeartbeatDirector", "exposureDeltaForTargetLuma$app", "float-zero", null, null, "HeartbeatDirectorTest"),
        new Mutation("Heartbeat tail sampling clock drifts", "HeartbeatPulseAudit", "tailSamplingTargetsUs$app", "constant", 1_000_000L, 999_999L, "HeartbeatPulseAuditTest")
    };

    private static class IsolatedLoader extends URLClassLoader {
        private final Mutation mutation;
        IsolatedLoader(URL[] urls, Mutation mutation) {
            super(urls, TestMutationCheck.class.getClassLoader());
            this.mutation = mutation;
        }
        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                if (!name.startsWith(PREFIX)) return super.loadClass(name, resolve);
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) loaded = findClass(name);
                if (resolve) resolveClass(loaded);
                return loaded;
            }
        }
        @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
            if (mutation == null || !name.equals(PREFIX + mutation.target())) return super.findClass(name);
            try (InputStream input = getResourceAsStream(name.replace('.', '/') + ".class")) {
                if (input == null) throw new ClassNotFoundException(name);
                byte[] original = input.readAllBytes();
                AtomicInteger edits = new AtomicInteger();
                ClassFile cf = ClassFile.of();
                byte[] changed;
                if (mutation.mode().equals("constant")) {
                    changed = cf.transformClass(cf.parse(original), ClassTransform.transformingMethodBodies(
                        method -> method.methodName().stringValue().equals(mutation.method()),
                        (builder, element) -> {
                            if (element instanceof ConstantInstruction constant &&
                                constant.constantValue().equals(mutation.from())) {
                                edits.incrementAndGet();
                                builder.loadConstant(mutation.to());
                            } else builder.with(element);
                        }));
                } else {
                    changed = cf.transformClass(cf.parse(original), (builder, element) -> {
                        if (element instanceof MethodModel method &&
                            method.methodName().stringValue().equals(mutation.method())) {
                            edits.incrementAndGet();
                            builder.withMethodBody(method.methodName(), method.methodType(), method.flags().flagsMask(),
                                code -> replacement(code, mutation.mode()));
                        } else builder.with(element);
                    });
                }
                if (edits.get() == 0) throw new IllegalStateException("Mutation did not match: " + mutation.label());
                return defineClass(name, changed, 0, changed.length);
            } catch (Exception error) {
                throw new ClassNotFoundException("Cannot apply mutation " + mutation.label(), error);
            }
        }
    }

    private static void replacement(CodeBuilder code, String mode) {
        if (mode.equals("true")) code.iconst_1().ireturn();
        else if (mode.equals("void")) code.return_();
        else if (mode.equals("float-zero")) code.fconst_0().freturn();
        else throw new IllegalArgumentException(mode);
    }

    private static Result run(URL[] urls, Mutation mutation, String test) throws Exception {
        try (IsolatedLoader loader = new IsolatedLoader(urls, mutation)) {
            if (mutation != null) Class.forName(PREFIX + mutation.target(), true, loader);
            Class<?> type = Class.forName(PREFIX + test, true, loader);
            return JUnitCore.runClasses(type);
        }
    }

    private static boolean infrastructureFailure(Throwable error) {
        if (error == null) return false;
        if (error instanceof LinkageError || error instanceof ClassNotFoundException) return true;
        if (error.getCause() != error && infrastructureFailure(error.getCause())) return true;
        for (Throwable suppressed : error.getSuppressed()) {
            if (infrastructureFailure(suppressed)) return true;
        }
        return false;
    }

    public static void main(String[] args) throws Exception {
        URL[] urls = Arrays.stream(System.getProperty("java.class.path").split(java.io.File.pathSeparator))
            .map(path -> {
                try { return Path.of(path).toUri().toURL(); }
                catch (Exception error) { throw new IllegalArgumentException(error); }
            }).toArray(URL[]::new);
        int killed = 0;
        StringBuilder json = new StringBuilder("{\n  \"mutations\": [\n");
        for (int index = 0; index < MUTATIONS.length; index++) {
            Mutation mutation = MUTATIONS[index];
            Result baseline = run(urls, null, mutation.test());
            if (!baseline.wasSuccessful() || baseline.getRunCount() == 0 || baseline.getIgnoreCount() > 0 ||
                baseline.getAssumptionFailureCount() > 0) {
                throw new IllegalStateException("Baseline is not clean for " + mutation.test() + ": " + baseline.getFailures());
            }
            Result altered = run(urls, mutation, mutation.test());
            boolean detected = altered.getRunCount() == baseline.getRunCount() &&
                altered.getIgnoreCount() == 0 && altered.getAssumptionFailureCount() == 0 &&
                altered.getFailures().stream().noneMatch(failure -> infrastructureFailure(failure.getException())) &&
                altered.getFailures().stream()
                .anyMatch(failure -> failure.getException() instanceof AssertionError);
            if (detected) killed++;
            System.out.println((detected ? "DETECTED: " : "SURVIVED: ") + mutation.label() +
                " (" + altered.getFailureCount() + " failures in " + altered.getRunCount() + " tests)");
            if (!detected) altered.getFailures().forEach(failure -> System.out.println(failure.getTrace()));
            json.append("    {\"label\": \"").append(mutation.label()).append("\", \"test\": \"")
                .append(mutation.test()).append("\", \"detected\": ").append(detected)
                .append(", \"failures\": ").append(altered.getFailureCount()).append("}")
                .append(index + 1 == MUTATIONS.length ? "\n" : ",\n");
        }
        json.append("  ],\n  \"detected\": ").append(killed).append(",\n  \"total\": ")
            .append(MUTATIONS.length).append("\n}\n");
        if (args.length > 0) {
            Path output = Path.of(args[0]);
            Files.createDirectories(output.toAbsolutePath().getParent());
            Files.writeString(output, json);
        }
        if (killed != MUTATIONS.length) throw new AssertionError("Some selected mutations were not detected");
    }
}
