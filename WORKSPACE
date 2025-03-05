workspace(name = "jnb-ping")

load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")

RULES_JVM_EXTERNAL_TAG = "5.3"

RULES_JVM_EXTERNAL_SHA = "d31e369b854322ca5098ea12c69d7175ded971435e55c18dd9dd5f29cc5249ac"

http_archive(
    name = "rules_jvm_external",
    sha256 = RULES_JVM_EXTERNAL_SHA,
    strip_prefix = "rules_jvm_external-%s" % RULES_JVM_EXTERNAL_TAG,
    url = "https://github.com/bazelbuild/rules_jvm_external/releases/download/%s/rules_jvm_external-%s.tar.gz" % (RULES_JVM_EXTERNAL_TAG, RULES_JVM_EXTERNAL_TAG),
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
KOTLIN_VERSION = "2.1.10"

KOTLIN_SHA = "c6e9e2636889828e19c8811d5ab890862538c89dc2a3101956dfee3c2a8ba6b1"

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

rules_kotlin_version = "v2.1.2"

rules_kotlin_sha = "6ea1c530261756546d0225a0b6e580eaf2f49084e28679a6c17f8ad1ccecca5d"

http_archive(
    name = "rules_kotlin",
    sha256 = rules_kotlin_sha,
    urls = ["https://github.com/bazelbuild/rules_kotlin/releases/download/%s/rules_kotlin-%s.tar.gz" % (rules_kotlin_version, rules_kotlin_version)],
)

load("@rules_kotlin//kotlin:repositories.bzl", "kotlin_repositories", "kotlinc_version")

kotlin_repositories(
    compiler_release = kotlinc_version(
        release = KOTLIN_VERSION,  # just the numeric version
        sha256 = KOTLIN_SHA,
    ),
)

load("@rules_kotlin//kotlin:core.bzl", "kt_register_toolchains")

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
