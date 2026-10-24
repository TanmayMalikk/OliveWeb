// Contains the source-size.gradle code for this part of Olive.
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

// Groups the data and actions for check source size.
abstract class CheckSourceSize : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:Input
    abstract val maximumLines: Property<Int>

    @TaskAction
    // Runs the verify size steps.
    fun verifySize() {
        // Comments help people understand the code, so only working source lines count toward the limit.
        val counts=sources.files.associateWith { file ->
            var insideBlockComment=false
            file.useLines { lines ->
                lines.count { line ->
                    val text=line.trim()
                    when {
                        text.isEmpty() -> false
                        insideBlockComment -> {
                            if(text.contains("*/")) insideBlockComment=false
                            false
                        }
                        text.startsWith("//") -> false
                        text.startsWith("/*") -> {
                            insideBlockComment=!text.contains("*/")
                            false
                        }
                        else -> true
                    }
                }
            }
        }
        val oversized=counts.filterValues { it>maximumLines.get() }
        if(oversized.isNotEmpty()) {
            throw GradleException(oversized.entries.joinToString("\n",
                prefix="Split these source files by responsibility (maximum ${maximumLines.get()} lines):\n") {
                "${it.key}: ${it.value} lines"
            })
        }
        logger.lifecycle("Source size check passed: ${counts.size} files, largest ${counts.values.maxOrNull() ?: 0} working source lines (comments excluded).")
    }
}

tasks.register<CheckSourceSize>("checkSourceSize") {
    group="verification"
    description="Keeps handwritten source files below 500 working source lines; comments do not count."
    maximumLines.set(500)
    sources.from(fileTree(rootDir) {
        include("**/*.kt","**/*.kts","**/*.java","**/*.js","**/*.jsx","**/*.ts","**/*.tsx","**/*.py","**/*.rules","**/*.css","**/*.html")
        exclude("**/.git/**","**/.gradle/**","**/.kotlin/**","**/.idea/**",
            "**/node_modules/**","**/build/**","**/build-*/**","**/dist/**","**/.next/**")
    })
}

tasks.register("checkArchitecture") {
    group="verification"
    description="Enforces feature folders and prevents Compose screens from constructing data dependencies."
    val screens=fileTree(rootDir) {
        include("app/src/main/java/com/olive/app/ui/**/*.kt")
        exclude(
            "app/src/main/java/com/olive/app/ui/covers/**/*.kt",
            "app/src/main/java/com/olive/app/ui/session/**/*.kt",
            "app/src/main/java/com/olive/app/ui/users/shared/navigation/SessionViewModel.kt",
        )
    }
    inputs.files(screens)
    inputs.dir("app/src/main/java/com/olive/app/ui")
    inputs.dir("app/src/main/java/com/olive/app/data")
    inputs.dir("web/src")
    doLast {
        val repositoryConstruction=Regex("\\b[A-Z][A-Za-z0-9]*Repository\\s*\\(")
        val dependencyViolations=screens.files.flatMap { file ->
            file.readLines().mapIndexedNotNull { index,line ->
                when {
                    repositoryConstruction.containsMatchIn(line) -> "${file.relativeTo(rootDir)}:${index+1} constructs a repository"
                    line.contains("import com.google.firebase") -> "${file.relativeTo(rootDir)}:${index+1} imports Firebase"
                    else -> null
                }
            }
        }
        val androidRoots=listOf(
            file("app/src/main/java/com/olive/app/ui") to "Android UI",
            file("app/src/main/java/com/olive/app/data") to "Android data",
        )
        val layoutViolations=androidRoots.flatMap { (directory,label) ->
            directory.listFiles().orEmpty()
                .filter { it.isFile && it.extension=="kt" }
                .map { "$label file ${it.relativeTo(rootDir)} must live in a feature folder" }
        }.toMutableList()
        val allowedAndroidRoles=setOf("user","safecontact","staff","admin","shared")
        val androidRoleRoot=file("app/src/main/java/com/olive/app/ui/users")
        layoutViolations += androidRoleRoot.walkTopDown()
            .filter { it.isFile && it.extension=="kt" }
            .mapNotNull { source ->
                val parts=source.relativeTo(androidRoleRoot).invariantSeparatorsPath.split('/')
                val declaredPackage=source.useLines { lines ->
                    lines.firstOrNull { it.startsWith("package ") }?.removePrefix("package ")?.trim()
                }
                val expectedPackage="com.olive.app.ui.users."+parts.dropLast(1).joinToString(".")
                when {
                    parts.size<2 -> "Role UI file ${source.relativeTo(rootDir)} must be grouped as users/<role>/<file> or users/<role>/<feature>/<file>"
                    parts.first() !in allowedAndroidRoles -> "Role UI file ${source.relativeTo(rootDir)} uses an unknown user type"
                    declaredPackage!=expectedPackage -> "Role UI file ${source.relativeTo(rootDir)} must declare package $expectedPackage"
                    else -> null
                }
            }.toList()

        val sharedUiRoot=file("app/src/main/java/com/olive/app/ui/shared")
        layoutViolations += sharedUiRoot.walkTopDown()
            .filter { it.isFile && it.extension=="kt" }
            .mapNotNull { source ->
                val declaredPackage=source.useLines { lines ->
                    lines.firstOrNull { it.startsWith("package ") }?.removePrefix("package ")?.trim()
                }
                if(declaredPackage=="com.olive.app.ui.shared") null
                else "Shared UI file ${source.relativeTo(rootDir)} must declare package com.olive.app.ui.shared"
            }.toList()

        val flatUiPackage=Regex("^package\\s+com\\.olive\\.app\\.ui\\s*$",RegexOption.MULTILINE)
        val uiRoot=file("app/src/main/java/com/olive/app/ui")
        layoutViolations += uiRoot.walkTopDown()
            .filter { it.isFile && it.extension=="kt" && flatUiPackage.containsMatchIn(it.readText()) }
            .map { "UI file ${it.relativeTo(rootDir)} uses the flat com.olive.app.ui package; declare its feature package instead" }
            .toList()

        val publicUiDeclaration=Regex(
            "^(?:public\\s+)?(?:(?:data|sealed|enum|value)\\s+)?(?:class|object|interface|fun|val|var|typealias)\\s+",
            RegexOption.MULTILINE,
        )
        layoutViolations += uiRoot.walkTopDown()
            .filter { it.isFile && it.extension=="kt" }
            .flatMap { source ->
                source.readLines().mapIndexedNotNull { index,line ->
                    if(publicUiDeclaration.containsMatchIn(line))
                        "UI declaration ${source.relativeTo(rootDir)}:${index+1} must be internal or private"
                    else null
                }
            }.toList()

        val organizedSourceRoots=listOf(
            file("app/src/main/java"),
            file("app/src/test/java"),
            file("app/src/androidTest/java"),
            file("web/src"),
        )
        layoutViolations += organizedSourceRoots
            .filter(File::exists)
            .flatMap { sourceRoot ->
                sourceRoot.walkTopDown()
                    .filter(File::isDirectory)
                    .filter { directory ->
                        directory.listFiles().orEmpty().count(File::isFile)==1 &&
                            directory.listFiles().orEmpty().none(File::isDirectory)
                    }
                    .map { directory ->
                        "One-file folder ${directory.relativeTo(rootDir)} should be combined with a related feature"
                    }
                    .toList()
            }
        val allowedWebRoles=setOf("user","safe_contact","staff","admin","shared")
        val webRoleRoot=file("web/src/users")
        layoutViolations += webRoleRoot.walkTopDown()
            .filter { it.isFile && it.extension in setOf("ts","tsx") }
            .mapNotNull { source ->
                val parts=source.relativeTo(webRoleRoot).invariantSeparatorsPath.split('/')
                when {
                    parts.size<2 -> "Role UI file ${source.relativeTo(rootDir)} must be grouped as users/<role>/<file> or users/<role>/<feature>/<file>"
                    parts.first() !in allowedWebRoles -> "Role UI file ${source.relativeTo(rootDir)} uses an unknown user type"
                    else -> null
                }
            }.toList()
        val allowedWebRootFiles=setOf("main.tsx","styles.css")
        layoutViolations += file("web/src").listFiles().orEmpty()
            .filter { it.isFile && it.name !in allowedWebRootFiles }
            .map { "Web source file ${it.relativeTo(rootDir)} must live in a feature folder" }
        val violations=dependencyViolations+layoutViolations
        if(violations.isNotEmpty()) throw GradleException(violations.joinToString("\n",
            prefix="Architecture boundary violations:\n"))
        logger.lifecycle("Architecture check passed: feature folders are enforced and screens depend on controllers rather than data implementations.")
    }
}
