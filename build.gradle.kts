plugins {
    id("java")
    id("com.xuncorp.spw.workshop") version "0.1.0-dev22"
}

group = "com.miniplayer"
version = "1.0.0"

val spwApiVersion = "0.1.0-dev22"

repositories {
    maven("https://maven.aliyun.com/repository/public")
    mavenCentral()
    maven("https://jitpack.io")
}

dependencies {
    compileOnly("com.github.Moriafly.spw-workshop-api:spw-workshop-api:$spwApiVersion")
    compileOnly("org.pf4j:pf4j:3.12.0")
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

spmod {
    config.PluginClass = "com.miniplayer.MiniPlayerPlugin"
    config.PluginId = "com.miniplayer.desktop-mini-player"
    config.PluginVersion = project.version.toString()
    config.PluginProvider = "miniplayer"
    config.PluginName = "桌面迷你播放条"
    config.PluginDescription = "桌面悬浮迷你播放条，显示当前曲目与逐字歌词"
    config.PluginHasConfig = false
    config.PluginOpenSourceUrl = "https://github.com/kirasu3579/desktop-mini-player"
}

tasks.named("jar") {
    // PF4J 需要插件清单；Gradle 插件会把 jar 内容平铺进 classes/
    (this as Jar).manifest {
        attributes(
            "Manifest-Version" to "1.0"
        )
    }
}
