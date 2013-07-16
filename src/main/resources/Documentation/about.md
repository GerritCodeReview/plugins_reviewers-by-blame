Summary
-------

A plugin that allows automatically adding reviewers to a change from the git
blame computation on the changed files. It will add the users as reviewers that
authored most of the lines touched by the change, since these users should be
familiar with the code and can mostly review the change.

Currently, the number of maximum reviewers added by this plugin is hardcoded to
3 users for every project.
