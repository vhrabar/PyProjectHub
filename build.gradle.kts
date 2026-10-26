import org.jetbrains.changelog.Changelog
import org.jetbrains.changelog.ChangelogSectionUrlBuilder
import org.jetbrains.changelog.markdownToHTML
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog")
    id("com.diffplug.spotless")
}

dependencies {
    testImplementation("junit:junit:4.13.2")

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        // Unified PyCharm (2025.3+); pycharmCommunity()/pycharmProfessional() only cover older releases
        pycharm("2025.3")
        bundledPlugin("PythonCore")
        testFramework(TestFrameworkType.Platform)
    }
}

changelog {
    version.set(providers.gradleProperty("version"))
    path.set(
        layout.projectDirectory
            .file("CHANGELOG.md")
            .asFile.canonicalPath,
    )
    repositoryUrl.set(providers.gradleProperty("pluginRepositoryUrl"))
    groups.set(emptyList())
    // Release tags are the bare version (no "v" prefix), as created by the Release workflow
    sectionUrlBuilder.set(
        ChangelogSectionUrlBuilder { repositoryUrl, currentVersion, previousVersion, isUnreleased ->
            when {
                isUnreleased && previousVersion != null -> "$repositoryUrl/compare/$previousVersion...HEAD"
                isUnreleased -> "$repositoryUrl/commits"
                previousVersion == null -> "$repositoryUrl/commits/$currentVersion"
                else -> "$repositoryUrl/compare/$previousVersion...$currentVersion"
            }
        },
    )
}

val changelogFile = layout.projectDirectory.file("CHANGELOG.md")
if (providers
        .fileContents(changelogFile)
        .asText.orNull
        .isNullOrBlank()
) {
    throw GradleException("CHANGELOG.md is missing or empty; plugin change notes cannot be rendered.")
}
val renderedChangeNotes =
    with(project.changelog) {
        renderItem(
            (getOrNull(providers.gradleProperty("version").get()) ?: getUnreleased())
                .withHeader(false)
                .withEmptySections(false),
            Changelog.OutputType.HTML,
        )
    }

intellijPlatform {
    pluginConfiguration {
        // Marketplace description is sourced from README.md between the marker comments
        description =
            providers.fileContents(layout.projectDirectory.file("README.md")).asText.map {
                val start = "<!-- Plugin description -->"
                val end = "<!-- Plugin description end -->"
                with(it.lines()) {
                    if (!containsAll(listOf(start, end))) {
                        throw GradleException("README.md is missing the plugin description section:\n$start ... $end")
                    }
                    subList(indexOf(start) + 1, indexOf(end)).joinToString("\n").let(::markdownToHTML)
                }
            }

        // "What's New" shown to users on install and on every update
        changeNotes.set(renderedChangeNotes)
    }
}

// pin JVM 21
kotlin {
    jvmToolchain(21)

    compilerOptions {
        jvmDefault = JvmDefaultMode.NO_COMPATIBILITY
    }
}

// ktlint-backed formatting: `./gradlew spotlessApply` to fix, `spotlessCheck` (wired into `check`) to verify.
spotless {
    kotlin {
        target("src/**/*.kt")
        ktlint()
        trimTrailingWhitespace()
        endWithNewline()
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktlint()
    }
}
