Configuration
=============

Two level of configurations are supported by this plugin. The optional file
`$site_path/etc/reviewers-by-blame.config` controls the function of this
plugin.

gerrit.explicitlyEnableProjects
:       If true, project must be explictly enabled to add reviewers to
        changes for this project.  By default, true.

project.NAME.enabled
:       If true, this project is enabled.  By default, false.

project.NAME.maxReviewers
:       The maximum number of reviewers that should be added to a change by
	this plugin.  By default 3.

Example 1: For all project on the site enable default behaivour: all projects
enabled and `maxReviewers` = 3:

```
  [gerrit]
    explicitlyEnableProjects = false
```
Example 2: Enable only project `foo`, all other projects are disabled:

```
  [project "foo"]
    enabled = true
    maxReviewers = 5
```

Example 3: Enable project `foo`, but disable project `bar`:

```
  [project "foo"]
    enabled = true
    maxReviewers = 5

  [project "bar"]
    enabled = false
    maxReviewers = 7

```

If the configuration file as descried above doesn't exist, then the
configuration can be provided in `project.config` file per project base.

If the configuration file as descried above doesn't exist, then the
configuration of the @PLUGIN@ plugin is done on project level in
the `project.config` file of the project. Missing values are inherited
from the parent projects. This means a global default configuration can
be done in the `project.config` file of the `All-Projects` root project.
Other projects can then override the configuration in their own
`project.config` file.

```
  [plugin "reviewers-by-blame"]
    maxReviewers = 2
```

plugin.reviewers-by-blame.maxReviewers
:	The maximum number of reviewers that should be added to a change by
	this plugin.

	By default 3.
