
description = "Kotlin Serialization Compiler Plugin"

plugins {
    kotlin("jvm")
    id("jps-compatible")
}
dependencies {
//    val compileOnly by configurations
//    val runtime by configurations
    compileOnly(intellijCoreDep()) { includeJars("intellij-core") }
    compileOnly(intellijDep())
    compileOnly(intellijPluginDep("maven"))
    compileOnly(project(":jps-plugin"))
    compileOnly(project(":compiler:plugin-api"))
    compileOnly(project(":compiler:frontend"))
    compileOnly(project(":compiler:backend"))
    compileOnly(project(":js:js.frontend"))
    compileOnly(project(":js:js.translator"))
    compileOnly(project(":idea"))
    compileOnly(project(":idea:idea-jps-common"))
    compileOnly(project(":idea:idea-gradle"))
    compileOnly(project(":idea:idea-maven"))

    runtime(projectRuntimeJar(":kotlin-compiler"))
    runtime(projectDist(":kotlin-stdlib"))
}

sourceSets {
    "main" { projectDefault() }
    "test" {}
}

val jar = runtimeJar {
    from(fileTree("$projectDir/src")) { include("META-INF/**") }
}

val serialPluginDir: File by rootProject.extra
dist(targetDir = File(serialPluginDir,"lib"), targetName = the<BasePluginConvention>().archivesBaseName.removePrefix("kotlin-") + ".jar")

ideaPlugin {
    from(jar)
    rename("^kotlin-", "")
}
