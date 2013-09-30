Summary
-------

A plugin that allows automatically adding reviewers to a change from the git
blame computation on the changed files. It will add the users as reviewers that
authored most of the lines touched by the change, since these users should be
familiar with the code and can mostly review the change.

The maximum number of reviewers that are added by this plugin can be
[configured per project](config.html).
