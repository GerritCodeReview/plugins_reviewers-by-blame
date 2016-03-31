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
    ignoreDrafts = true
    ignoreShortMessageRegEx = WIP(.*)
```

plugin.reviewers-by-blame.maxReviewers
:	The maximum number of reviewers that should be added to a change by
	this plugin.

	By default 3.


plugin.reviewers-by-blame.ignoreDrafts
:	Ignore draft commits when adding reviewers.

	By default false.


plugin.reviewers-by-blame.ignoreShortMessageRegEx
:	Ignore commits where the first line of the commit messages matches
    the given regular expression. If empty, no commits are ignored.

	By default not set.