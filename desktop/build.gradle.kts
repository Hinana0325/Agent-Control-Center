import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    // 与 android/ 端同源：Kotlin 2.4.10（CMP 1.12.0 基于 Kotlin 2.2.20 构建，
    // 2.4.10 编译器向前兼容其产物）
    kotlin("jvm") version "2.4.10"
    kotlin("plugin.compose") version "2.4.10"
    kotlin("plugin.serialization") version "2.4.10"
    id("org.jetbrains.compose") version "1.12.0"
}

dependencies {
    // Compose Desktop（按当前 OS 解析 skiko 变体）
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)

    // 网络层与 Android 端同栈：Ktor 3 + OkHttp 引擎（WebSocket + HTTP/SSE）
    implementation("io.ktor:ktor-client-core:3.2.3")
    implementation("io.ktor:ktor-client-okhttp:3.2.3")
    implementation("io.ktor:ktor-client-websockets:3.2.3")

    // 传输层 wire 协议 JSON 解析（与 Android 端一致）
    implementation("com.google.code.gson:gson:2.10.1")
    // 本地持久化 JSON 序列化（默认值语义安全，区别于 Gson 反射构造）
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    testImplementation(kotlin("test"))
}

kotlin {
    // 运行于 JDK 17+（沙箱/CI 为 JDK 25，编译目标 17 保证 jpackage 产物兼容性）
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 17
}

tasks.test {
    useJUnit()
}

compose.desktop {
    application {
        mainClass = "com.agentcontrolcenter.desktop.MainKt"

        nativeDistributions {
            // Windows(Msi) / macOS(Dmg) / Linux(Deb) 三平台安装包
            targetFormats(TargetFormat.Msi, TargetFormat.Dmg, TargetFormat.Deb)

            packageName = "AgentControlCenter"
            packageVersion = "5.3.0"
            description = "Agent Control Center — unified control center for local & remote AI agents"
            vendor = "Agent Control Center"
            copyright = "© 2026 Agent Control Center. All rights reserved."

            windows {
                menu = true
                shortcut = true
                dirChooser = true
                upgradeUuid = "8f4a2c31-9b7d-4e6a-b5c8-1d2e3f4a5b6c"
            }
            macOS {
                bundleID = "com.agentcontrolcenter.desktop"
            }
            linux {
                menuGroup = "Agent Control Center"
                debMaintainer = "support@agentcontrolcenter.app"
            }
        }
    }
}
