load("//tools/bzl:plugin.bzl", "gerrit_plugin")

gerrit_plugin(
    name = "reviewers-by-blame",
    srcs = glob(["src/main/java/**/*.java"]),
    resources = glob(["src/main/resources/**/*"]),
    manifest_entries = [
        "Gerrit-PluginName: reviewers-by-blame",
        "Gerrit-Module: com.googlesource.gerrit.plugins.reviewersbyblame.ReviewersByBlameModule",
        "Implementation-Title: Reviewers By Blame",
        "Implementation-URL: https://gerrit.googlesource.com/plugins/reviewers-by-blame",
    ],
