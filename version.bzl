"""Single source of truth for the jnb-ping version.

The repo-root `VERSION` file is the one place the version is edited. This module
extension reads it and exposes `JNB_PING_VERSION` to `BUILD.bazel`, so the Bazel
build, Maven coordinates, and distribution artifacts all derive from that file.
"""

def _jnb_ping_version_repo_impl(rctx):
    version = rctx.read(rctx.attr.version_file).strip()
    rctx.file("BUILD.bazel", "")
    rctx.file("version.bzl", "JNB_PING_VERSION = \"{}\"\n".format(version))

_jnb_ping_version_repo = repository_rule(
    implementation = _jnb_ping_version_repo_impl,
    attrs = {"version_file": attr.label(allow_single_file = True, mandatory = True)},
)

def _jnb_ping_version_ext_impl(_module_ctx):
    _jnb_ping_version_repo(name = "jnb_ping_version", version_file = Label("//:VERSION"))

jnb_ping_version_ext = module_extension(implementation = _jnb_ping_version_ext_impl)
