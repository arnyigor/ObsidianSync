import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(projects.shared)

    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutinesSwing)
    implementation(libs.ktor.client.cio)

    implementation(libs.compose.uiToolingPreview)

    testImplementation(kotlin("test"))
}

compose.desktop {
    application {
        mainClass = "ru.arny.obsidiansync.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Exe, TargetFormat.Deb)
            includeAllModules = true
            packageName = "ObsiDelta Sync"
            packageVersion = "1.0.1"
            windows {
                iconFile.set(project.file("src/main/resources/icon.ico"))
            }
            linux {
                iconFile.set(project.file("src/main/resources/icon.png"))
            }
            macOS {
                iconFile.set(project.file("src/main/resources/icon.icns"))
            }
        }
    }
}
