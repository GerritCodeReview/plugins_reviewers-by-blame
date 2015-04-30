include_defs('//bucklets/gerrit_plugin.bucklet')

gerrit_plugin(
  name = 'reviewers-by-blame',
  srcs = glob(['src/main/java/**/*.java']),
  resources = glob(['src/main/**/*']),
  manifest_entries = [
    'Implementation-Title: Reviewers By Blame',
    'Implementation-URL: https://gerrit.googlesource.com/plugins/reviewers-by-blame',
    'Gerrit-PluginName: reviewers-by-blame',
    'Gerrit-ApiType: plugin',
    'Gerrit-ApiVersion: 2.12-SNAPSHOT',
    'Gerrit-Module: com.googlesource.gerrit.plugins.reviewersbyblame.ReviewersByBlameModule',
  ],
)

