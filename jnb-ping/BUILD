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
