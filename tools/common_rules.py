import os.path

def symlink_tree(name, srcs):
    cmds = []
    cmds.append('mkdir -p $OUT')

    created_parents = set()

    for target, src in srcs.iteritems():
        if src.startswith(':') or src.startswith('//'):
            # These are actually in $SRCDIR too, but
            # it's too hard to tell what the path is.
            the_src = '$(location ' + src + ')'
        else:
            the_src = '$SRCDIR/' + src

        parent = os.path.dirname(target)
        if parent and parent not in created_parents:
            cmds.append('mkdir -p "$OUT/' + parent + '"')

        cmds.append('ln -s "' + the_src + '" "$OUT/' + target + '"')

    genrule(
        name=name,
        out=name,
        srcs=srcs.values(),
        cmd=' && '.join(cmds),
    )
