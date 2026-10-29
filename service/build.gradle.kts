plugins {
    id("org.springframework.boot") version "3.5.6" apply false
}

val springBootVersion = "3.5.6"

subprojects {
    apply(plugin = "java-library")

    group = "ru.itmo.advisor"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    dependencies {
        // Версии библиотек — из BOM Spring Boot, в модулях версии не пишем.
        "api"(platform("org.springframework.boot:spring-boot-dependencies:$springBootVersion"))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testImplementation"("org.assertj:assertj-core")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all,-processing,-serial"))
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}
