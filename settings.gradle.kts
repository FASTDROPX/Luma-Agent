pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories {
    google(); mavenCentral()
    exclusiveContent {
        forRepository { ivy { name="SherpaOfficialRelease"; url=uri("https://github.com/k2-fsa/sherpa-onnx/releases/download")
            patternLayout { artifact("v[revision]/sherpa-onnx-[revision].[ext]") };metadataSources {artifact()} } }
        filter {includeModule("com.k2fsa", "sherpa-onnx")}
    }
} }
rootProject.name = "LumaAgent"
include(":app")
