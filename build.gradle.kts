plugins {
    java
}

group = "cn.huohuas001.huhobot"
version = "0.1.0-beta.1"

repositories {
    mavenCentral()
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
}

dependencies {
    compileOnly("org.spigotmc:spigot-api:1.16.5-R0.1-SNAPSHOT")

    testImplementation("org.spigotmc:spigot-api:1.16.5-R0.1-SNAPSHOT")
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(8)
}

tasks.processResources {
    filteringCharset = "UTF-8"
    val values = mapOf("version" to project.version)
    inputs.properties(values)
    filesMatching("plugin.yml") {
        expand(values)
    }
}

tasks.jar {
    archiveBaseName.set("HuHoBotSetup")
    from("LICENSE.txt") {
        into("META-INF")
    }
}

tasks.test {
    useJUnitPlatform()
}
