plugins { java; application }
group = "io.github.almeida96"
version = "0.1.0"
repositories { mavenCentral() }
dependencies {
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.4")
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.1.3")
}
java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }
application { mainClass = "io.github.almeida96.tooltape.Main" }
sourceSets.main { resources.srcDir("examples") }
tasks.test { useJUnitPlatform() }
tasks.withType<JavaCompile> { options.encoding = "UTF-8"; options.release = 21 }
dependencyLocking { lockAllConfigurations() }
distributions { main { contents {
    from("README.md", "LICENSE", "CHANGELOG.md")
    from("docs") { into("docs") }
    from("examples") { into("examples") }
}}}
