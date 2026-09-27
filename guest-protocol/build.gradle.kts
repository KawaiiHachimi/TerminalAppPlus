import com.google.protobuf.gradle.*
plugins { `java-library`; id("com.google.protobuf") }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
protobuf {
    protoc { artifact = "com.google.protobuf:protoc:3.25.5" }
    plugins { create("grpc") { artifact = "io.grpc:protoc-gen-grpc-java:1.79.0" } }
    generateProtoTasks { all().configureEach {
        builtins { named("java") { option("lite") } }
        plugins { create("grpc") { option("lite") } }
    } }
}

dependencies {
 api("io.grpc:grpc-protobuf-lite:1.79.0")
 api("io.grpc:grpc-stub:1.79.0")
 compileOnly("javax.annotation:javax.annotation-api:1.3.2")
}
