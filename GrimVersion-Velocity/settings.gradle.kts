pluginManagement {
  repositories {
    gradlePluginPortal()
    mavenCentral()
  }
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
    mavenCentral()
  }
}

rootProject.name = "GrimVersion"
