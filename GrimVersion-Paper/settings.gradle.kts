pluginManagement {
  repositories {
    gradlePluginPortal()
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
  }
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
    mavenCentral()
  }
}

rootProject.name = "GrimVersion-Paper"
