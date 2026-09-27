plugins {
  java
}

group = "jp.example"
version = "1.0.0"

java {
  toolchain {
    languageVersion.set(JavaLanguageVersion.of(21))
  }
}

dependencies {
  compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
}

tasks {
  compileJava {
    options.encoding = "UTF-8"
    options.release.set(21)
  }

  processResources {
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
      expand("version" to project.version)
    }
  }

  jar {
    archiveBaseName.set("GrimVersion-Paper")
    archiveVersion.set(project.version.toString())
  }
}
