## Loonie


Loonie is Ladder's Clojure and Clojurescript build system. It uses buck to cache intermediate output so you don't have to recompile every file on every change.

_**ALPHA SOFTWARE:**_ Loonie is still considered alpha-level software. There are quite a few hacks required to make it work for now. However, Ladder does ship code to production using the build system.

### Quick start
To get started using Loonie, clone this repository and [install buck](https://buckbuild.com/setup/install.html). Then you should be able to run all the simple tests:

```
$ buck test //examples/...
[-] PROCESSING BUCK FILES...FINISHED 0.4s [100%] 🐌
[-] DOWNLOADING... (0.00 B/S AVG, TOTAL: 0.00 B, 0 Artifacts)
[-] BUILDING...FINISHED 73.4s [100%] (264/264 JOBS, 264 UPDATED, 260 [98.5%] CACHE MISS)
[-] TESTING...FINISHED 4.6s (2 PASS/0 FAIL)
RESULTS FOR //examples/ladder:common-lib-test_cljs_run //examples/ladder:test_run
PASS      4.6s  1 Passed   0 Skipped   0 Failed   //examples/ladder:common-lib-test_cljs_run
PASS      1.8s  1 Passed   0 Skipped   0 Failed   //examples/ladder:test_run
TESTS PASSED
```

If you make a change to `examples/ladder/common_lib.cljc`, and re-run the command, you'll notice that only a very small portion of the files is recompiled:

```
$ echo '(defn i-dont-do-anything [] (println "Yep"))' >> examples/ladder/common_lib.cljc
$ buck test //examples/...
[-] PROCESSING BUCK FILES...FINISHED 0.2s [100%] 🐌  (Watchman overflow)
[-] DOWNLOADING... (0.00 B/S AVG, TOTAL: 0.00 B, 0 Artifacts)
[-] BUILDING...FINISHED 12.1s [100%] (100/100 JOBS, 22 UPDATED, 20 [20.0%] CACHE MISS)
[-] TESTING...FINISHED 4.5s (2 PASS/0 FAIL)
RESULTS FOR //examples/ladder:common-lib-test_cljs_run //examples/ladder:test_run
PASS      4.5s  1 Passed   0 Skipped   0 Failed   //examples/ladder:common-lib-test_cljs_run
PASS      1.5s  1 Passed   0 Skipped   0 Failed   //examples/ladder:test_run
TESTS PASSED
```

### FAQ

#### Should I use this? ####
The biggest benefits of using buck come from using the [artifact cache](https://buckbuild.com/concept/buckconfig.html#cache). It's especially useful if you have several developers working on the same codebase since the cache can be distributed. You may also see better CI times with buck caching if you configure your CI to cache the `buck-out` directory between runs.

Buck is also quite good at having multiple dependent build steps. Your build is actually a DAG of operations in Buck. If you need to generate a file to bundle into your jar's resources during the build, buck can do that without any external scripting.

If you do use this project, let us know - we'd love to hear how you use it and what issues you run into!

#### Does this integrate with leiningen/boot? ####
There is no leiningen or boot integration at this time. PRs are welcome!

#### How do I add maven/clojars jars? ####
Facebook's buck comes with a tool to do this. If you clone [buck's repo](https://github.com/facebook/buck), you can run `buck run maven-importer` to see how it works. There is a `resolver.json` in this repository that is used to configure the importer and a handy `make resolver` command that will re-run the resolver to generate the `third_party` directory.

#### How can I add lint/static analysis/magic? ####
Buck is a very versatile tool. One of the ways to add functionality is to wrap existing build rules. For example, you could redefine `clojure_library` to automatically generate a `cljfmt_lint` test for each of the `srcs`.

Doing this is left as an exercise to the reader. If you actually do that though, PRs are welcome!

#### Can I disable Clojure AOT? ####
Sure. You can build with `clojure.compilation-enabled=0`, e.g. `buck test --config clojure.compilation-enabled=0 ...`.

If you want to use it on only one rule, append `_clj_uncompiled` to the name of the rule, e.g. `buck build //examples/ladder:main_clj_uncompiled`.

#### I added a dependency, but buck can't see it! ####
Buck inherently doesn't know about dependencies between clojure namespaces. To help it, we have a tool called `buck-autodeps` (not to be confused with the `buck autodeps` command). In this repo, you can run `make autodeps` which will rewrite the `BUCK.autodeps` files. You'll need to run it whenever you change `:require` clauses.

#### I added a new namespace but buck can't see it! ####
You need to tell buck that this is a namespace. (TODO: this should likely be done by `make autodeps`.)

1. Create a `BUCK` file in the directory of the namespace. For a basic namespace, just put `load_clojure_autodeps()` as the contents.
2. Re-run `buck-autodeps` and `git add` any `BUCK.autogenerated` files.
3. Profit. (`buck test` should work)

#### Autodeps doesn't see my namespace! ####
Make sure your namespace is under `examples` or you changed how the autodeps command is being run to discover your sources.

If you did that, and it still doesn't see it, that's weird. [File an issue](issues/new).

#### Autodeps doesn't see my new test! ####
The criteria for being a test is a `:require` of `cljs.test` or `clojure.test`. Feel free to submit a PR for your test framework of choice!

#### Autodeps can't find one of my java(script) dependencies! ####
The autodeps parser currently doesn't detect `:import` dependencies. You can work around this by changing the `BUCK` file to include the extra dependencies, e.g.

```python
load_clojure_autodeps({
    'my-cool-ns': {
        'java_deps': [
            '//third_party/cool:thing',
        ],
    },
    'my-cool-ns_cljs': {
        'compile_deps': [
            '//third_party/cool:thing-for-cljs',
        ],
    },
})
```

#### Can I read resources during compilation? ####
Yes! If you're using autodeps, you're using `clojure_library` rules under the covers which support a `resources` argument. It works the same as a `java_library`'s resources. The resources will also be visible at compile (i.e. macro-evaluation) time.

#### This is painfully slow during development! ####
You are unfortunately correct. We don't use the buck build during development internally and have a small hack to start a nREPL with the classpath set correctly.

#### I can't find my answer in this list ####
[File an issue](issues/new).
