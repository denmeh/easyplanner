plugins {
    application
}

val boltffiJavaDist: Directory =
    layout.projectDirectory.dir("../crates/easyplanner-app/dist/java")

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

application {
    mainClass = "com.example.easyplanner_javademo.Main"
}

tasks.jar {
    manifest {
        attributes("Main-Class" to application.mainClass.get())
    }
}

sourceSets {
    named("main") {
        java.srcDir("src/main/java")
        java.srcDir(boltffiJavaDist)
    }
}

tasks.processResources {
    val nativeHostDir = boltffiJavaDist.dir("native/windows-x86_64").asFile
    if (nativeHostDir.isDirectory) {
        from(nativeHostDir) {
            into("native/windows-x86_64")
        }
    }
}

tasks.compileJava {
    doFirst {
        val dist = boltffiJavaDist.asFile
        if (!dist.isDirectory) {
            throw GradleException(
                "Missing BoltFFI Java output at ${dist.absolutePath}. " +
                    "Run from crates/easyplanner-app: boltffi pack java --release"
            )
        }
    }
}
