import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.analysis.ICounter;
import org.jacoco.core.data.ExecutionData;
import org.jacoco.core.tools.ExecFileLoader;

class DeviceTestLines {
    public static void main(String[] args) throws Exception {
        Path results = Path.of(args[0]);
        Path destination = Path.of(args[1]);
        Files.createDirectories(destination.getParent());
        List<Path> snapshots;
        try (var files = Files.list(results)) {
            snapshots = files.filter(path -> path.toString().endsWith(".ec")).sorted().toList();
        }
        try (var output = new PrintWriter(Files.newBufferedWriter(destination, StandardCharsets.UTF_8))) {
            for (Path snapshot : snapshots) {
                var loader = new ExecFileLoader();
                loader.load(snapshot.toFile());
                Set<String> hitClasses = new HashSet<>();
                loader.getExecutionDataStore().getContents().stream()
                    .filter(ExecutionData::hasHits).forEach(data -> hitClasses.add(data.getName()));
                var coverage = new CoverageBuilder();
                var analyzer = new Analyzer(loader.getExecutionDataStore(), coverage);
                for (int i = 2; i < args.length; i++) {
                    Path classes = Path.of(args[i]);
                    for (String name : hitClasses) {
                        Path file = classes.resolve(name + ".class");
                        if (Files.isRegularFile(file)) analyzer.analyzeAll(file.toFile());
                    }
                }
                if (coverage.getClasses().stream().allMatch(item -> item.isNoMatch())) continue;
                String test = snapshot.getFileName().toString().replaceFirst("\\.ec$", "");
                output.printf("%s\t\t%n", test);
                for (var source : coverage.getSourceFiles()) {
                    String lines = IntStream.rangeClosed(source.getFirstLine(), source.getLastLine())
                        .filter(line -> line > 0 && (source.getLine(line).getStatus() & ICounter.FULLY_COVERED) != 0)
                        .mapToObj(Integer::toString).collect(Collectors.joining(","));
                    if (!lines.isEmpty()) output.printf("%s\t%s/%s\t%s%n", test, source.getPackageName(), source.getName(), lines);
                }
            }
            if (output.checkError()) throw new IllegalStateException("Could not write device coverage details");
        }
    }
}
