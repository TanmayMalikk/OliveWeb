import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

abstract class CheckSourceSize : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:Input
    abstract val maximumLines: Property<Int>

    @TaskAction
    fun verifySize() {
        val counts=sources.files.associateWith { file -> file.useLines { it.count() } }
        val oversized=counts.filterValues { it>maximumLines.get() }
        if(oversized.isNotEmpty()) {
            throw GradleException(oversized.entries.joinToString("\n",
                prefix="Split these source files by responsibility (maximum ${maximumLines.get()} lines):\n") {
                "${it.key}: ${it.value} lines"
            })
        }
        logger.lifecycle("Source size check passed: ${counts.size} files, largest ${counts.values.maxOrNull() ?: 0} lines.")
    }
}

tasks.register<CheckSourceSize>("checkSourceSize") {
    group="verification"
    description="Keeps handwritten source files below the 500-line limit."
    maximumLines.set(500)
    sources.from(fileTree(rootDir) {
        include("**/*.kt","**/*.kts","**/*.java","**/*.js","**/*.jsx","**/*.ts","**/*.tsx","**/*.py","**/*.rules","**/*.css","**/*.html")
        exclude("**/.git/**","**/.gradle/**","**/.kotlin/**","**/.idea/**",
            "**/node_modules/**","**/build/**","**/build-*/**","**/dist/**","**/.next/**")
    })
}
