import os.path

include_defs('//tools/common_rules.py')
include_defs('//tools/clojure/edn.py')

COMPILATION_ENABLED = bool(int(read_config('clojure', 'compilation-enabled', '1')))

CLOJURE_COMPILERS = {
    'tainted': '$(worker //tools/clojure:persistent-clj-tainted-worker) \'%s\'',
    'worker': '$(worker //tools/clojure:persistent-clj-worker) \'%s\'',
    'simple': 'java -cp \'%s\' clojure.main',
}

CLOJURE_COMPILER = CLOJURE_COMPILERS[read_config('clojure', 'compiler-type', 'tainted')]


def clojure_library(name, srcs, compile=True, use_worker=True,
                    deps=[], java_deps=[], resources=[], visibility=[],
                    needs_compile=False, symlink_tree_path=''):
    if isinstance(srcs, list):
        srcs = {s: s for s in srcs}
    if isinstance(resources, list):
        resources = {s: s for s in resources}
    original_deps = deps

    deps = list(frozenset(deps + [
        '//third_party/clojure:clojure',
    ]))

    if compile:
        deps = [d + '_clj' for d in deps]

    deps += java_deps

    java_binary(
        name=name + '_clj_deps',
        deps=deps,
    )

    if not symlink_tree_path:
        symlink_tree(
            name=name + '_symlink_tree',
            srcs=flatten_dicts(srcs, resources),
        )
        symlink_tree_path = '$(location :' + name + '_symlink_tree)'

    if compile and (COMPILATION_ENABLED or needs_compile):
        cmd = []

        classpath = [symlink_tree_path,
                     '$TMP/compile',
                     '$(classpath :' + name + '_clj_deps)']

        if use_worker:
            compiler = CLOJURE_COMPILER
        else:
            compiler = CLOJURE_COMPILERS['simple']

        cmd.append(compiler % (':'.join(classpath),) + ' $(location //tools/clojure:compile_clj.clj) ' + ' $OUT ' + symlink_tree_path)

        genrule(
            name=name + '_compile',
            out=name,
            cmd=' && '.join(cmd),
        )

        prebuilt_jar(
            name=name + '_lib',
            binary_jar=':' + name + '_compile',
            deps=deps,
        )
    else:
        prebuilt_jar(
            name=name + '_lib',
            binary_jar=symlink_tree_path[len('$(location '):-1],
            deps=deps,
        )

    java_library(
        name=name,
        deps=[':' + name + '_lib'],
        resources=resources.values(),
        visibility=visibility,
    )

    java_library(
        name=name + '_clj',
        exported_deps=[':' + name],
        visibility=['PUBLIC'] if 'PUBLIC' in visibility else sum([
            [v + '_clj_deps', v + '_clj'] for v in visibility
        ], [])
    )

    if compile:
        deps = [d + '_clj_uncompiled' for d in original_deps]
        clojure_library(
            name=name + '_clj_uncompiled',
            compile=False,
            use_worker=use_worker,
            srcs=srcs,
            deps=deps,
            java_deps=java_deps,
            visibility=visibility,
            resources=resources,
            symlink_tree_path=symlink_tree_path,
        )
            

def clojure_test(name, srcs={}, libraries=[], deps=[], resources=[], **kwargs):
    if isinstance(srcs, list):
        srcs = {s: s for s in srcs}

    if not libraries:
        clojure_library(
            name=name + '_lib',
            compile=False,
            srcs=srcs,
            deps=deps,
            resources=resources,
        )
        libraries = [':' + name + '_lib']

    java_library(
        name=name + '_bin',
        deps=[x + '_clj' for x in libraries] +
              ['//tools/clojure:test-runner'],
        resources=resources,
    )

    sh_test(
        name=name,
        test='//tools/clojure:java-runner',
        args=['-cp', '$(classpath :' + name + '_bin)', 'BuckTestRunner'] +
              ['$(location ' + x + '_symlink_tree)' for x in libraries],
        **kwargs
    )

def clj_build_prebuilt_jar(
        real_prebuilt_jar, name, deps=[], visibility=[], **kwargs):
    java_library(
        name=name + '_clj_uncompiled',
        exported_deps=[':' + name],
        visibility=['PUBLIC'] if 'PUBLIC' in visibility else sum([
            [v + '_clj_uncompiled'] for v in visibility
        ], []),
    )

    if get_base_path() == 'third_party/clojure' and name == 'clojure':
        java_library(
            name=name + '_clj',
            exported_deps=[':' + name],
            visibility=['PUBLIC'],
        )
        return

    java_binary(
        name=name + '_clj_deps',
        deps=[d + '_clj' for d in deps] + ['//third_party/clojure:clojure'],
    )

    cmd = []
    cmd.append('mkdir -p $TMP/compile')

    classpath = ['$(location :' + name + '_clj_extract)',
                 '$(classpath :' + name + '_clj_deps)']

    cmd.append('cd $(location :' + name + '_clj_extract)')
    cmd.append('java -cp ' + ':'.join(classpath) + ' clojure.main $(location //tools/clojure:compile_clj.clj) ' + ' $TMP/compile $(location :' + name + '_clj_extract)')
    cmd.append('jar cf $OUT -C $TMP/compile .')
    genrule(
        name=name + '_clj_compile',
        out=name + '.jar',
        cmd=' && '.join(cmd),
    )

    real_prebuilt_jar(
        name=name + '_clj',
        binary_jar=':' + name + '_clj_compile',
        deps=[d + '_clj' for d in deps] + [':' + name],
        visibility=['PUBLIC'] if 'PUBLIC' in visibility else sum([
            [v + '_clj_deps', v + '_clj'] for v in visibility
        ], [])
    )
