load("@rules_kotlin//kotlin:jvm.bzl", "kt_jvm_test")

def kotlin_test(name, testclass, deps):
    kt_jvm_test(
        name = name,
        srcs = native.glob(["test/**/*.kt"]),
        jvm_flags = ["-Djdk.attach.allowAttachSelf"],
        local = True,
        test_class = testclass,
        deps = deps,
    )
