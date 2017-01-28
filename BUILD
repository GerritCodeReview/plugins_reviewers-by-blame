load(
    "//tools/bzl:plugin.bzl",
    "gerrit_plugin",
    "PLUGIN_DEPS",
    "PLUGIN_TEST_DEPS",
)

gerrit_plugin(
    name = "reviewers-by-blame",
    srcs = glob(["src/main/java/**/*.java"]),
    resources = glob(["src/main/**/*"]),
    manifest_entries = [
        "Gerrit-PluginName: reviewers-by-blame",
        "Gerrit-ApiType: plugin",
        "Gerrit-Module: com.googlesource.gerrit.plugins.reviewersbyblame.ReviewersByBlameModule",
        "Implementation-Title: Reviewers By Blame",
        "Implementation-URL: https://gerrit.googlesource.com/plugins/reviewers-by-blame",
    ],
)
