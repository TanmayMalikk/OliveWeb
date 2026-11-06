// Enforces the feature and dependency boundaries described in the architecture guide.
import org.gradle.api.GradleException

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
        val productionKotlin=fileTree("app/src/main/java") { include("**/*.kt") }.files
        val sourceLayoutViolations=buildList {
            if(productionKotlin.size>100) add(
                "Android production source has ${productionKotlin.size} Kotlin files; keep the consolidated layout at 100 or fewer"
            )
            productionKotlin.filter { it.readLines().size>600 }.forEach { source ->
                add("Android source ${source.relativeTo(rootDir)} exceeds the 600-line limit")
            }
        }
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

        // A single-file feature folder is allowed. Combining tiny related files remains a review guideline,
        // but it is not an architecture boundary and must not fail builds or test runs.
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
        val violations=dependencyViolations+layoutViolations+sourceLayoutViolations
        if(violations.isNotEmpty()) throw GradleException(violations.joinToString("\n",
            prefix="Architecture boundary violations:\n"))
        logger.lifecycle("Architecture check passed: feature folders are enforced and screens depend on controllers rather than data implementations.")
    }
}
