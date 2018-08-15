
plugins {
    kotlin("jvm")
    id("jps-compatible")
}

dependencies {
    compileOnly(project(":idea"))
    compileOnly(project(":idea:idea-maven"))
    compileOnly(project(":idea:idea-gradle"))
    compileOnly(project(":idea:idea-jvm"))

    compile(intellijDep())

    runtimeOnly(files(toolsJar()))
}

val ideaPluginDir: File by rootProject.extra
val ideaSandboxDir: File by rootProject.extra
val serialPluginDir: File by rootProject.extra

runIdeTask("runIde", ideaPluginDir, ideaSandboxDir, serialPluginDir) {
    // TODO: add serialization plugin to pluginDir
    dependsOn(":dist", ":ideaPlugin", ":kotlinx-serialization-compiler-plugin:dist")
}
