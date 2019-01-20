Configuration
=============

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
```

plugin.reviewers-by-blame.maxReviewers
:	The maximum number of reviewers that should be added to a change by
	this plugin.

	By default 3.

plugin.reviewers-by-blame.ignoreFileRegEx
:	Ignore files where the filename matches the given regular expression when
	computing the reviewers. If empty or not set, no files are ignored.

	By default not set.

plugin.reviewers-by-blame.ignoreSubjectRegEx
:	Ignore commits where the subject of the commit messages matches
	the given regular expression. If empty or not set, no commits are ignored.

	By default not set.

plugin.reviewers-by-blame.ignoredUsers
:	A blacklist used to ignore commits if it matches the specified user.

	By default not set.

To configure suggest reviewers do the following:

reviewers-by-blame.enableSuggestReviewers
:	This will disable using the events to suggest reviewers, rather use a button.

	By default set to false.
