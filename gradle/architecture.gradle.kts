// Source-level boundaries complement the actual Gradle boundary around :core:domain.
val checkArchitecture by tasks.registering {
    group = "verification"
    description = "Reject misplaced packages and forbidden dependencies between application layers."
    val appSources = fileTree("app/src/main/java") { include("**/*.kt") }
    val domainSources = fileTree("core/domain/src/main/kotlin") { include("**/*.kt") }
    inputs.files(appSources, domainSources)
    doLast {
        val errors = mutableListOf<String>()
        val appRoot = file("app/src/main/java")
        val domainRoot = file("core/domain/src/main/kotlin")
        val namespace = "com.sweefl.player."
        for (source in appSources + domainSources) {
            val text = source.readText()
            val sourceRoot = if (source in domainSources.files) domainRoot else appRoot
            val expected = source.parentFile.relativeTo(sourceRoot).invariantSeparatorsPath.replace('/', '.')
            val actual = Regex("(?m)^package ([\\w.]+)$").find(text)?.groupValues?.get(1)
            val path = source.relativeTo(projectDir).invariantSeparatorsPath
            if (actual != expected) errors += "$path: package $actual does not match directory $expected"
            val owner = actual?.removePrefix(namespace).orEmpty()
            val imports = Regex("(?m)^import ([\\w.*]+)").findAll(text).map { it.groupValues[1] }.toList()
            if (sourceRoot == domainRoot && imports.any { it.startsWith("android.") || it.startsWith("androidx.") }) {
                errors += "$path: JVM domain rules must not depend on Android or AndroidX"
            }
            if (owner.startsWith("data.") && imports.any { it.startsWith("androidx.compose.") }) {
                errors += "$path: persistence must not depend on Compose"
            }
            for (dependency in imports.filter { it.startsWith(namespace) }) {
                val target = dependency.removePrefix(namespace)
                val allowed = when {
                    owner.startsWith("core.designsystem") -> target.startsWith("core.designsystem.")
                    owner.startsWith("core.") -> target.startsWith("core.") || target.startsWith("domain.")
                    owner.startsWith("data.") -> target.startsWith("data.") || target.startsWith("core.model.") || target.startsWith("domain.")
                    owner.startsWith("playback.") -> target.startsWith("playback.") || target.startsWith("core.") || target.startsWith("domain.")
                    owner.startsWith("feature.") -> target != "MainActivity" && target != "PlayerActivity" && target != "SweeflApplication" && !target.startsWith("di.")
                    else -> true // application composition root
                }
                if (!allowed) errors += "$path: forbidden dependency $dependency"
                if ((owner.contains(".ui") || owner == "feature.network" || source.name == "SettingsDialog.kt") && target.startsWith("data.")) {
                    errors += "$path: rendering UI must receive state/actions rather than access storage"
                }
            }
            if (actual == "com.sweefl.player" && source.name !in setOf("MainActivity.kt", "PlayerActivity.kt", "SweeflApplication.kt")) {
                errors += "$path: root package is reserved for Android entry points"
            }
        }
        check(errors.isEmpty()) { "Architecture violations:\n" + errors.joinToString("\n") }
        logger.lifecycle("Architecture boundaries verified for ${appSources.files.size + domainSources.files.size} Kotlin files")
    }
}

tasks.register("verify") {
    group = "verification"
    description = "Build debug APK and run architecture checks, JVM/Android unit tests and Android lint."
    dependsOn(checkArchitecture, ":core:domain:test", ":app:assembleDebug", ":app:testDebugUnitTest", ":app:lintDebug")
}
