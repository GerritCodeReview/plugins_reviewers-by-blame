Build
=====

This plugin is built with Buck.

Clone or link this plugin to the plugins directory of Gerrit's source
tree, and issue the command:

```
  buck build plugins/reviewers-by-blame
```

The output is created in

```
  buck-out/gen/plugins/reviewers-by-blame/reviewers-by-blame.jar
```

This project can be imported into the Eclipse IDE:

```
  ./tools/eclipse/project.py
```
