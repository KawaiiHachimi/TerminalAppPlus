plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "com.android.virtualization.terminal"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.android.virtualization.terminal.plus"
        minSdk = 37
        targetSdk = 37
        versionCode = 1
        versionName = "17.0-plus.1"
    }
    buildFeatures { compose = true; aidl = false; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging { resources.excludes += setOf("META-INF/INDEX.LIST", "META-INF/io.netty.versions.properties", "net/jpountz/util/**/*.so", "net/jpountz/util/**/*.dylib", "net/jpountz/util/**/*.dll") }
}
dependencies {
    implementation("at.yawk.lz4:lz4-java:1.12.0")
    testImplementation("junit:junit:4.13.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    debugImplementation(project(":terminal-view"))
    debugImplementation("androidx.drawerlayout:drawerlayout:1.2.0")
    implementation(project(":guest-protocol"))
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    compileOnly(files("libs/android-hidden-37.jar"))
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("com.google.android.material:material:1.13.0")
    implementation(platform("androidx.compose:compose-bom:2026.03.01"))
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.material3.adaptive:adaptive:1.2.0")
    implementation("androidx.compose.material3.adaptive:adaptive-layout:1.2.0")
    implementation("androidx.compose.material3.adaptive:adaptive-navigation:1.2.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-service:2.10.0")
    implementation("androidx.lifecycle:lifecycle-process:2.10.0")
    implementation("androidx.navigation:navigation-fragment-ktx:2.9.7")
    implementation("androidx.window:window:1.5.1")
    implementation("androidx.work:work-runtime-ktx:2.11.1")
    implementation("com.google.code.gson:gson:2.13.2")
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("io.grpc:grpc-okhttp:1.79.0")
    implementation("io.grpc:grpc-protobuf-lite:1.79.0")
    implementation("io.grpc:grpc-stub:1.79.0")
    compileOnly("javax.annotation:javax.annotation-api:1.3.2")
}

// Soong enables modern AIDL explicitly; invoke the SDK compiler with the same API floor.
val sdkDir = androidComponents.sdkComponents.sdkDirectory
val generateStandaloneAidl = tasks.register("generateStandaloneAidl") {
    val source = file("src/main/aidl")
    val output = layout.buildDirectory.dir("generated/standaloneAidl")
    inputs.dir(source)
    outputs.dir(output)
    doLast {
        val sdk = sdkDir.get().asFile
        val out = output.get().asFile
        out.mkdirs()
        source.walkTopDown().filter { it.extension == "aidl" }.forEach { aidl ->
            providers.exec {
                commandLine("$sdk/build-tools/36.0.0/aidl", "--lang=java", "--min_sdk_version=37", "--rpc",
                    "-I${source.absolutePath}", "-p$sdk/platforms/android-37.0/framework.aidl",
                    "-o${out.absolutePath}", aidl.absolutePath)
            }.result.get().assertNormalExitValue()
        }
    }
}
android.sourceSets["main"].java.directories += layout.buildDirectory.dir("generated/standaloneAidl").get().asFile.absolutePath
tasks.named("preBuild").configure { dependsOn(generateStandaloneAidl) }
