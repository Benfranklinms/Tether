plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.detekt)
    alias(libs.plugins.roborazzi) apply false
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("config/detekt.yml"))
    source.setFrom(
        files(
            "core/src/main/kotlin", "core/src/test/kotlin",
            "app/src/main/kotlin", "app/src/test/kotlin",
        ),
    )
    parallel = true
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    jvmTarget = "17"
    reports {
        html.required.set(true)
        sarif.required.set(false)
        xml.required.set(false)
        txt.required.set(true)
    }
}

// Local sandbox builds can redirect outputs (CI leaves this unset).
System.getenv("TETHER_BUILD_DIR")?.let { dir ->
    allprojects { layout.buildDirectory.set(file("$dir/${project.name}")) }
}
