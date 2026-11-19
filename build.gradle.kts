plugins {
    id("java-library")
    id("xyz.jpenilla.run-paper") version "3.1.0"
}

val faweVersion = "2.16.2"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.+")
    compileOnly("com.fastasyncworldedit:FastAsyncWorldEdit-Bukkit:$faweVersion") { isTransitive = false }
    compileOnly("com.fastasyncworldedit:FastAsyncWorldEdit-Core:$faweVersion") { isTransitive = false }
    compileOnly("com.fastasyncworldedit:FastAsyncWorldEdit-Libs-Core:$faweVersion") { isTransitive = false }
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

tasks {
    runServer {
        minecraftVersion("26.3")
        downloadPlugins {
            github("IntellectualSites", "FastAsyncWorldEdit", faweVersion, "FastAsyncWorldEdit-Paper-$faweVersion.jar")
            modrinth("axiom-paper-plugin", "V2yAy4up") // 6.0.1+26.3
        }
        jvmArgs("-Xms2G", "-Xmx2G", "-Dcom.mojang.eula.agree=true")
    }

    processResources {
        val props = mapOf("version" to version)
        inputs.properties(props)
        filesMatching("paper-plugin.yml") {
            expand(props)
        }
    }
}
