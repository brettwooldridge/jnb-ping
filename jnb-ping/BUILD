load("@io_bazel_rules_kotlin//kotlin:core.bzl", "kt_javac_options", "kt_kotlinc_options")
load("@io_bazel_rules_kotlin//kotlin:jvm.bzl", "kt_jvm_library")

kt_jvm_library(
    name = "com-zaxxer-ping",
    srcs = glob([
        "src/**/*.kt",
    ]),
    kotlinc_opts = "//:kt_kotlinc_perf_options",
    tags = [
        "manual",
        "maven_coordinates=com.zaxxer:jnb-ping:{pom_version}",
    ],
    visibility = ["//visibility:public"],
    deps = [
        "@maven//:com_github_jnr_jffi",
        "@maven//:com_github_jnr_jnr_constants",
        "@maven//:com_github_jnr_jnr_ffi",
        "@maven//:com_github_jnr_jnr_posix",
        "@maven//:it_unimi_dsi_fastutil",
        "@maven//:org_jetbrains_kotlin_kotlin_reflect",
    ],
)

load(":TEST.bzl", "kotlin_test")

kotlin_test(
    name = "test",
    testclass = "com.zaxxer.ping.PingTest",
    deps = [
        ":com-zaxxer-ping",
        "@maven//:junit_junit",
    ],
)

load("@graknlabs_bazel_distribution//maven/templates:rules.bzl", "assemble_maven", "deploy_maven")

assemble_maven(
    name = "assemble-maven",
    developers = {
        "1": [
            "name=Brett Wooldridge",
            "email=brett.wooldridge@gmail.com",
            "organization=com.zaxxer",
        ],
    },
    license = "apache",
    package = "{maven_packages}",
    project_description = "Java Non-Blocking Ping (ICMP)",
    project_name = "jnb-ping",
    project_url = "https://github.com/brettwooldridge/jnb-ping",
    scm_url = "https://github.com/brettwooldridge/jnb-ping.git",
    target = ":com-zaxxer-ping",
    version_file = "//central-sync:VERSION",
)

deploy_maven(
    name = "deploy-maven",
    deployment_properties = "//central-sync:deployment.properties",
    target = ":assemble-maven",
)
