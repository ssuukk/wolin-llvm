plugins {
    kotlin("jvm") version "2.2.10"
    id("java")
}

repositories { mavenCentral() }

dependencies {
    implementation("org.antlr:antlr4-runtime:4.13.1")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.11.4")
    testImplementation(kotlin("test"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin { jvmToolchain(21) }

val antlrTool: Configuration by configurations.creating
dependencies { antlrTool("org.antlr:antlr4:4.13.1") }

val antlrSrc = layout.projectDirectory.dir("src/main/antlr")
val antlrOut = layout.buildDirectory.dir("generated/antlr")
val antlrClasses = layout.buildDirectory.dir("classes/antlr")

val generateParser by tasks.registering {
    val tool = antlrTool
    val srcDir = antlrSrc
    val outDir = antlrOut
    inputs.dir(srcDir)
    outputs.dir(outDir)
    doLast {
        val out = outDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        val classpath = tool.resolve().joinToString(File.pathSeparator) { it.absolutePath }
        val grammars = srcDir.asFile.listFiles { f -> f.name.endsWith(".g4") }!!.sorted()
        val cmd = listOf("java", "-cp", classpath, "org.antlr.v4.Tool",
                         "-visitor", "-package", "pl.qus.wolin.parser",
                         "-lib", srcDir.asFile.absolutePath, "-o", out.absolutePath) +
                  grammars.map { it.absolutePath }
        val proc = ProcessBuilder(cmd).inheritIO().start()
        if (proc.waitFor() != 0) throw GradleException("ANTLR failed")
    }
}

val compileAntlr by tasks.registering(JavaCompile::class) {
    dependsOn(generateParser)
    source(antlrOut)
    classpath = files(configurations["compileClasspath"])
    destinationDirectory.set(antlrClasses)
    options.release.set(21)
}

tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileKotlin") {
    dependsOn(compileAntlr)
    libraries.from(antlrClasses)
}

tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileTestKotlin") {
    dependsOn(compileAntlr)
    libraries.from(antlrClasses)
}

tasks.test {
    useJUnitPlatform()
    classpath += files(antlrClasses)
}
