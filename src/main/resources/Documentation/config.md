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
    strategy = by_blame
    maxReviewers = 2
```

plugin.reviewers-by-blame.maxReviewers
:	The maximum number of reviewers that should be added to a change by
	this plugin.

	By default 3.

plugin.reviewers-by-blame.strategy
:	Strategy for adding the reviewers: BY_BLAME or FIXED.
	Default is BY_BLAME.

plugin.reviewers-by-blame.reviewer
:	reviewer is for strategy FIXED.

For example with this configuration 3 fixed reviewers are always added
to all changes:

```
  [plugin "reviewers-by-blame"]
    strategy = fixed
    reviewer = 1000021
    reviewer = 1000022
    reviewer = 1000023
```
