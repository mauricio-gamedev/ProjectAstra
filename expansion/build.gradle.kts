plugins {
    kotlin("jvm")
}

group = "io.github.astromg01.astra"
version = "0.1.0"

kotlin {
    jvmToolchain(17)
}

tasks.test {
    useJUnitPlatform()
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
}
