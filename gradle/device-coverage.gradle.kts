import org.gradle.testing.jacoco.tasks.JacocoReport
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.gradle.api.tasks.JavaExec

// Device tests run one method per process; merge every execution file with the JVM tests.
apply(plugin = "jacoco")

// Robolectric loads Android-backed application classes through a sandbox classloader.
tasks.withType<Test>().configureEach {
    extensions.findByType(JacocoTaskExtension::class.java)?.apply {
        isIncludeNoLocationClasses = true
        excludes = excludes.orEmpty() + "jdk.internal.*"
    }
}

val kotlinDebugClasses = layout.buildDirectory.dir("intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes")
val javaDebugClasses = layout.buildDirectory.dir("intermediates/javac/debug/compileDebugJavaWithJavac/classes")

tasks.register<JacocoReport>("combinedDebugCoverageReport") {
    group = "verification"
    description = "Combines debug JVM and device UI-test coverage without excluding application features."
    dependsOn("compileDebugKotlin", "compileDebugJavaWithJavac")
    executionData.from(fileTree(layout.buildDirectory) {
        include("outputs/unit_test_code_coverage/debugUnitTest/*.exec")
        include("outputs/device-test-coverage/*.ec")
    })
    classDirectories.from(
        fileTree(kotlinDebugClasses),
        fileTree(javaDebugClasses) {
            exclude("**/R.class", "**/R$*.class", "**/BuildConfig.class")
        },
    )
    sourceDirectories.from(files("src/main/java", "src/main/kotlin"))
    reports {
        xml.required.set(true)
        html.required.set(true)
        xml.outputLocation.set(layout.buildDirectory.file("reports/coverage/combinedDebug/report.xml"))
        html.outputLocation.set(layout.buildDirectory.dir("reports/coverage/combinedDebug/html"))
    }
    doFirst {
        check(executionData.files.any { it.isFile }) { "No debug coverage data found. Run unit/device tests first." }
    }
}

tasks.register<JavaExec>("deviceTestLineDetails") {
    group = "verification"
    dependsOn("compileDebugKotlin", "compileDebugJavaWithJavac")
    val snapshots = layout.buildDirectory.dir("outputs/device-test-coverage")
    val details = layout.buildDirectory.file("reports/coverage/device-test-lines.tsv")
    val analyzer = rootProject.file("scripts/DeviceTestLines.java")
    onlyIf { snapshots.get().asFile.isDirectory }
    classpath = configurations.getByName("jacocoAnt")
    mainClass.set(analyzer.absolutePath)
    args(snapshots.get().asFile, details.get().asFile, kotlinDebugClasses.get().asFile, javaDebugClasses.get().asFile)
    inputs.files(analyzer, snapshots, kotlinDebugClasses, javaDebugClasses)
    outputs.file(details)
}
