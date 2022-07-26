load("@rules_jvm_external//:defs.bzl", "maven_install")
load("@rules_jvm_external//:specs.bzl", "maven", "parse")
load("//:import_external_alias.bzl", "import_external_alias")

KOTLIN_VERSION = "1.7.0"

def dependency(coordinates, exclusions = None):
    artifact = parse.parse_maven_coordinate(coordinates)
    return maven.artifact(
        group = artifact["group"],
        artifact = artifact["artifact"],
        packaging = artifact.get("packaging"),
        classifier = artifact.get("classifier"),
        version = artifact["version"],
        exclusions = exclusions,
    )

deps = [
    dependency("com.github.jnr:jnr-posix:3.1.15"),
    dependency("com.github.jnr:jnr-constants:0.10.3"),
    dependency("com.github.jnr:jnr-ffi:2.2.12"),
    dependency("com.github.jnr:jffi:1.3.9"),
    dependency("it.unimi.dsi:fastutil:8.5.4"),
    dependency("junit:junit:4.13.2"),
    dependency("org.jetbrains.kotlin:kotlin-reflect:%s" % KOTLIN_VERSION),
    dependency("org.jetbrains.kotlin:kotlin-stdlib-common:%s" % KOTLIN_VERSION),
    dependency("org.jetbrains.kotlin:kotlin-stdlib-jdk8:%s" % KOTLIN_VERSION),
]

def dependencies():
    maven_install(
        artifacts = deps,
        repositories = [
            "https://repo.maven.apache.org/maven2/",
            "https://mvnrepository.com/artifact",
            "https://maven-central.storage.googleapis.com",
        ],
        generate_compat_repositories = True,
        # maven_install_json = "//:maven_install.json",
    )
