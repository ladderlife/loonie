include_defs('//tools/common_rules.py')
include_defs('//tools/clojure/edn.py')

CLJS_COMPILATION_PROFILES = {
    'advanced': {
        ':optimizations': ':advanced',
        ':source-map': True,
        ':closure-defines': {"goog.DEBUG": False},
        ':elide-asserts': True,
        ':output-wrapper': True,
    },
    'whitespace': {
        ':optimizations': ':whitespace',
        ':source-map': True,
    },
    'none': {
        ':optimizations': ':none',
        ':source-map': False,
    },
}

CLJS_COMPILER_RUNNERS = {
    'worker': lambda cp: '$(worker //tools/clojure:persistent-clj-worker) \'' + cp + '\'',
    'nailgun': lambda cp: '$(location //tools/clojure:ng) PersistentClj \'' + cp + '\'',
    'standalone': lambda cp: 'java -cp \'' + cp + '\' clojure.main',
}
CLJS_COMPILER_RUNNER = CLJS_COMPILER_RUNNERS[read_config('clojurescript', 'compiler-type', 'worker')]

def cljs_library(name, srcs, deps=[], compile_deps=[], compiler_options={}, 
                 externs=[], resources=[], visibility=[]):
    """Produce a clojurescript library.

    A library is a collection of files (ideally 1) with some set of dependencies.
    They aren't useful by themselves, but provide a unit of incremental compilation.

    See `cljs_binary` for the final js output.
    """
    if isinstance(srcs, list):
        srcs = {s: s for s in srcs}
    if isinstance(resources, list):
        resources = {s: s for s in resources}
    if isinstance(externs, list):
        externs = {e: e for e in externs}

    deps = list(frozenset(
            deps +
            ['//third_party/clojure:clojurescript']))

    compile_deps = list(frozenset(
        compile_deps +
        ['//third_party/clojure:clojurescript']))

    tree = flatten_dicts(srcs, resources, externs)

    if externs:
        lines = '\n'.join("echo " + to_edn_arg(v) + " >> $OUT"
                          for v in externs.keys())

        genrule(
            name=name + '_externs',
            out="deps.cljs",
            cmd="""
set -e
echo "{:externs [" >> $OUT
%s
echo "]}" >> $OUT
""" % (lines,))

        tree['deps.cljs'] = ':' + name + '_externs'

    symlink_tree(
        name=name + '_symlink_tree',
        srcs=tree,
    )

    _compile_cljs_profiles(
        name=name,
        src=':' + name + '_symlink_tree',
        deps=deps,
        java_deps=compile_deps,
        compiler_options=compiler_options,
        visibility=visibility,
    )

    # TODO(mikekap): Specify cljs deps far more precisely.
    # We depend on '_clj' for all java_deps (to catch compiled clj deps).
    # However, we don't distinguish deps & java_deps well in cljs_library
    # targets, so sometimes cljs-only libs come in as java_deps, which
    # doesn't end well. Leave this dummy java_library here for that case.
    java_library(
        name=name + '_clj',
        deps=[
            '//third_party/clojure:clojurescript',
        ],
    )

    # Dummy rule with the original name - we need the _cljs
    # for dependencies.
    genrule(
        name=name,
        out=name,
        srcs=[':' + name + '_none_transitive_cljs'],
        cmd='ln -s $SRCS $OUT',
    )

def cljs_binary(name, main, src, profile='advanced', resource_path='', compiler_options={}, visibility=[]):
    """Produces a final javascript binary from a set of clojurescript libraries.

    The "binary" in this case would be suitable for running on your browser."""

    resource_path = resource_path or name
    compiler_options = flatten_dicts(
        CLJS_COMPILATION_PROFILES[profile],
        compiler_options,
        {':final-output': True, ':main': main})

    genrule(
        name=name,
        out=resource_path,
        # TODO(mikekap): Fix strange compilation failure when using the worker
        # compiler.
        cmd=CLJS_COMPILER_RUNNERS['standalone']('$(classpath //tools/clojurescript:compile_cljs_deps)') + " $(location //tools/clojurescript:compile_cljs.clj) " + to_edn_arg(compiler_options) + " $OUT $(location " + src + "_" + profile + "_transitive_cljs)",
        visibility=visibility,
    )

    java_library(
        name=name + '_java_lib',
        resources=[':' + name],
        visibility=visibility,
    )

def cljs_build_prebuilt_jar(
        real_prebuilt_jar, name, deps=[], visibility=[], **kwargs):
    """To extract cljs libraries from .jars & cache them, we monkey-patch prebuilt_jar."""

    _compile_cljs_profiles(
        name=name,
        # TODO(mikekap): This can be a lot more cache-friendly if we
        # read the jar directly.
        src=':' + name + '_clj_extract',
        deps=deps,
        # TODO(mikekap): Make the java deps more precise. Particularly
        # we should disable classpath-based source loading.
        java_deps=[':' + name],
        visibility=visibility,
    )

def _compile_cljs_profiles(name, deps, visibility, **kwargs):
    for profile in CLJS_COMPILATION_PROFILES.keys():
        _compile_cljs(
            name=name + '_' + profile,
            profile=profile,
            deps=[d + '_' + profile for d in deps],
            visibility=[
                'PUBLIC' if v == 'PUBLIC' else v + '_' + profile
                for v in visibility
            ],
            **kwargs
        )

def _compile_cljs(name, src, profile, java_deps, deps=[],
                  compiler_options={}, visibility=[]):
    java_deps = set(java_deps)
    compiler_options = flatten_dicts(CLJS_COMPILATION_PROFILES[profile],
                                     compiler_options)

    cljs_visibility = sum([
        [v] if v == 'PUBLIC' else [v + '_cljs_compile',
                                   v + '_transitive_cljs']
        for v in visibility
    ], [])

    # Don't compile clojure as a closure library.
    if get_base_path() == 'third_party/clojure' and name in {'clojure', 'google-closure-library', 'google-closure-library-third-party'}:
        genrule(
            name=name + '_cljs',
            out=name + '_cljs',
            cmd="mkdir -p $OUT",
            visibility=cljs_visibility,
        )

        genrule(
            name=name + '_transitive_cljs',
            out=name + '_transitive_cljs',
            cmd="mkdir -p $OUT && echo | gzip > $OUT/ijavascript.edn.gz",
            visibility=cljs_visibility,
        )
        return

    # Implicitly all clojurescript compilation targets depend on clojure
    # and clojurescript.
    java_deps.update([
        '//tools/clojurescript:compile_cljs_deps',
    ])

    java_library(
        name=name + '_cljs_deps',
        deps=java_deps,
    )

    dep_args = [
        '$(location ' + dep + '_cljs)'
        for dep in deps
    ]

    genrule(
        name=name + '_cljs_compile',
        out=name,
        cmd=CLJS_COMPILER_RUNNER('$(classpath :' + name + '_cljs_deps)') + ' '
            '$(location //tools/clojurescript:compile_cljs.clj) ' +
            to_edn_arg(compiler_options) + " "
            '$OUT $(location ' + src + ') ' + ' '.join(dep_args),
    )

    # _cljs targets are used in other cljs_library (but not cljs_binary)
    # rules. These are the "interface" that this target exposes (akin to
    # java's ABI jars). The main reason for doing this is avoiding
    # transitive recompilation - if the interface doesn't change after
    # compile runs, upstream targets don't need to be recompiled.
    #
    # Note that cljs_binary doesn't use these as it needs the actual
    # javascript output.
    genrule(
        name=name + '_cljs',
        out=name,
        cmd='mkdir -p $OUT && '
            'cp $(location :' + name + '_cljs_compile)/deps.cljs $OUT/ && '
            'cp $(location :' + name + '_cljs_compile)/all-namespaces* $OUT/',
        visibility=cljs_visibility,
    )

    # This is the output for cljs_binary.
    transitive_dep_args = [
        '$(location ' + d + '_transitive_cljs)/'
        for d in deps
    ]

    # TODO(mikekap): Replace this with a clojure script that uses nippy
    # for de-duping.
    cmds = ['mkdir -p $OUT']
    cmds.append(
        'rsync -alb --ignore-existing --exclude all-namespaces* --exclude deps.cljs --exclude ijavascript.edn.gz ' +
        '$(location :' + name + '_cljs_compile)/ ' +
        ' '.join(transitive_dep_args) + ' $OUT/')
    cmds.append('cp $(location :' + name + '_cljs)/* $OUT/')

    cmd = ['gunzip -c']
    cmd.append('$(location :' + name + '_cljs_compile)/ijavascript.edn.gz')
    for d in deps:
        cmd.append('$(location ' + d + '_transitive_cljs)/ijavascript.edn.gz')
    cmd.append('| sort -u | gzip > $OUT/ijavascript.edn.gz')
    cmds.append(' '.join(cmd))

    genrule(
        name=name + '_transitive_cljs',
        out=name,
        cmd=' && '.join(cmds),
        visibility=cljs_visibility,
    )

def cljs_test(name, library=None, namespaces=[], srcs={}, deps=[], compile_deps=[]):
    runner = name + '-runner'
    runner_ns = 'ladder.' + runner
    runner_rule = ':' + runner
    runner_file = runner_ns.replace('.', '/').replace('-', '_') + '.cljs'

    lib = name + '-lib'
    lib_rule = ':' + lib

    bin = name + '-bin'

    if not library:
        filenames_no_extensions = map(lambda x: x.replace('.cljc', '').replace('.cljs', ''), srcs.keys()) # HACK use regex
        namespaces = map(lambda x: x.replace('/', '.').replace('_', '-'), filenames_no_extensions)
    namespaces_quoted = map(lambda x: "'" + x, namespaces)
    namespaces_brackets = map(lambda x: '[' + x + ']', namespaces)

    test_and_runner_srcs = srcs.copy()
    runner_src = {runner_file: runner_rule}
    test_and_runner_srcs.update(runner_src)

    runner_cljs = '''
(ns ladder.cljs-test-runner
  (:require [cljs.test :refer [report] :refer-macros [run-tests]]
            {namespaces_brackets}))

(enable-console-print!)

(defmethod report [:cljs.test/default :end-run-tests]
  [{{:keys [pass fail error]}} _ _]
  (js/alert (str "phantom-exit-code:" (if (or (> fail 0) (> error 0) (= pass 0)) 42 0))))

(defn ^:export go! []
  (run-tests {namespaces_quoted}))
    '''.format(runner_ns=runner_ns,
               namespaces_quoted=' '.join(namespaces_quoted),
               namespaces_brackets='\n'.join(namespaces_brackets))

    genrule(
        name=runner,
        out=runner,
        cmd="cat <<EOF > $OUT\n{runner_cljs}\nEOF".format(runner_cljs=runner_cljs)
    )

    if library:
        cljs_library(
            name=lib,
            srcs= runner_src,
            deps=[library],
            compile_deps=compile_deps,
        )

    else:
        cljs_library(
            name=lib,
            srcs=test_and_runner_srcs,
            deps=deps,
            compile_deps=compile_deps,
        )

    cljs_binary(
        name=bin,
        src=lib_rule,
        profile='none',
            main="ladder.cljs-test-runner",
    )

    sh_test(
        name=name,
        test='//tools/clojurescript:phantomjs',
        args=['$(location //tools/clojurescript:cljs_test_runner.js)',
              '$(location :' + bin + ')/index.html'],
        labels=['cljs']
    )
