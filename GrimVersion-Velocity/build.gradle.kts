plugins {
  java
  id("com.gradleup.shadow") version "8.3.5"
}

group = "jp.example"
version = "1.0.0"

java {
  toolchain {
    languageVersion.set(JavaLanguageVersion.of(21))
  }
}

dependencies {
  compileOnly("com.velocitypowered:velocity-api:3.4.0-SNAPSHOT")
  annotationProcessor("com.velocitypowered:velocity-api:3.4.0-SNAPSHOT")
  implementation("com.electronwill.night-config:toml:3.8.1")
}

tasks {
  compileJava {
    options.encoding = "UTF-8"
    options.release.set(21)
  }

  shadowJar {
    archiveBaseName.set("GrimVersion")
    archiveClassifier.set("")
    archiveVersion.set(project.version.toString())
    exclude("META-INF/versions/**")
  }

  build {
    dependsOn(shadowJar)
  }
}
