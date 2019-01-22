@PLUGIN@ Configuration
======================

The configuration of the @PLUGIN@ plugin is done on project level in
the `project.config` file of the project. Missing values are inherited
from the parent projects. This means a global default configuration can
be done in the `project.config` file of the `All-Projects` root project.
Other projects can then override the configuration in their own
`project.config` file.

```
  [plugin "reviewers-by-blame"]
    maxReviewers = 2
    ignoreSubjectRegEx = WIP(.*)
	onBehalfOf = 1000000
```

plugin.@PLUGIN@.maxReviewers
:	The maximum number of reviewers that should be added to a change by
	this plugin.

	By default 3.

plugin.@PLUGIN@.ignoreFileRegEx
:	Ignore files where the filename matches the given regular expression when
	computing the reviewers. If empty or not set, no files are ignored.

	By default not set.

plugin.@PLUGIN@.ignoreSubjectRegEx
:	Ignore commits where the subject of the commit messages matches
	the given regular expression. If empty or not set, no commits are ignored.

	By default not set.

plugin.@PLUGIN@.onBehalfOf
:	Assign reviewers on behalf of another user identity.

	By default not set and change owner identity is used.
