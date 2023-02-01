workspace(name = "jnb-ping")

load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")
load("@bazel_tools//tools/build_defs/repo:git.bzl", "git_repository")

RULES_JVM_EXTERNAL_TAG = "4.5"

RULES_JVM_EXTERNAL_SHA = "b17d7388feb9bfa7f2fa09031b32707df529f26c91ab9e5d909eb1676badd9a6"

http_archive(
    name = "rules_jvm_external",
    sha256 = RULES_JVM_EXTERNAL_SHA,
    strip_prefix = "rules_jvm_external-%s" % RULES_JVM_EXTERNAL_TAG,
    url = "https://github.com/bazelbuild/rules_jvm_external/archive/%s.zip" % RULES_JVM_EXTERNAL_TAG,
)

load("@rules_jvm_external//:repositories.bzl", "rules_jvm_external_deps")

rules_jvm_external_deps()

load("@rules_jvm_external//:setup.bzl", "rules_jvm_external_setup")

rules_jvm_external_setup()

http_archive(
    name = "rules_pkg",
    sha256 = "eea0f59c28a9241156a47d7a8e32db9122f3d50b505fae0f33de6ce4d9b61834",
    urls = [
        "https://mirror.bazel.build/github.com/bazelbuild/rules_pkg/releases/download/0.8.0/rules_pkg-0.8.0.tar.gz",
        "https://github.com/bazelbuild/rules_pkg/releases/download/0.8.0/rules_pkg-0.8.0.tar.gz",
    ],
)

load("@rules_pkg//:deps.bzl", "rules_pkg_dependencies")

rules_pkg_dependencies()

#################################################################################################
#                                      MAVEN DEPENDENCIES
#################################################################################################

# KOTLIN_SHA must be updated when KOTLIN_VERSION is updated.
# It is the SHA-256 of kotlin-compiler-KOTLIN_VERSION.zip.
# SHA can be found on https://github.com/JetBrains/kotlin/releases
KOTLIN_VERSION = "1.7.21"

KOTLIN_SHA = "8412b31b808755f0c0d336dbb8c8443fa239bf32ddb3cdb81b305b25f0ad279e"

load("@rules_jvm_external//:defs.bzl", "maven_install")
load("@rules_jvm_external//:specs.bzl", "maven")
load("//:third_party.bzl", "dependencies")

dependencies()

load("@maven//:defs.bzl", "pinned_maven_install")

pinned_maven_install()

load("@maven//:compat.bzl", "compat_repositories")

compat_repositories()

#################################################################################################
#                                      Kotlin Setup
#################################################################################################

rules_kotlin_version = "v1.7.1"

rules_kotlin_sha = "fd92a98bd8a8f0e1cdcb490b93f5acef1f1727ed992571232d33de42395ca9b3"

http_archive(
    name = "io_bazel_rules_kotlin",
    sha256 = rules_kotlin_sha,
    urls = ["https://github.com/bazelbuild/rules_kotlin/releases/download/%s/rules_kotlin_release.tgz" % rules_kotlin_version],
)

load("@io_bazel_rules_kotlin//kotlin:repositories.bzl", "kotlin_repositories", "kotlinc_version")

kotlin_repositories(
    compiler_release = kotlinc_version(
        release = KOTLIN_VERSION,  # just the numeric version
        sha256 = KOTLIN_SHA,
    ),
)

load("@io_bazel_rules_kotlin//kotlin:core.bzl", "kt_register_toolchains")

register_toolchains("//:kotlin_toolchain")

################################################################################################
#
################################################################################################

#load("@jnb-ping//central-sync:dependencies.bzl", "graknlabs_bazel_distribution")
#
#graknlabs_bazel_distribution()
#
#http_archive(
#    name = "rules_python",
#    sha256 = "a3a6e99f497be089f81ec082882e40246bfd435f52f4e82f37e89449b04573f6",
#    strip_prefix = "rules_python-0.10.2",
#    url = "https://github.com/bazelbuild/rules_python/archive/refs/tags/0.10.2.tar.gz",
#)
