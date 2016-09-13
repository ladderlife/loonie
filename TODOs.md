## TODOs

 - [ ] Make autodeps read `:import` directives correctly.
 - [ ] Add lint support via `cljfmt` or `eastwood`.
 - [ ] Upstream changes to the clojurescript compiler (see `tools/clojurescript/compile_cljs.clj`)
 - [ ] Use a thread-local `PrintStream` to use in the clojure test runner to enable parallel testing.
 - [ ] Add support to generate a source file tree that something like figwheel can pick up. This can use the same decorator pattern that `_clj_uncompiled` uses.
 - [ ] Create "clojure ABI files" and compile code with them instead of pulling in the transitive dependency tree. The idea being to clear the function bodies. This can get rid of tainted workers.
 - [ ] Move the clj test runner to it's own project.
 - [ ] Move `persistent-clj` to it's own project.
 - [ ] [Bazel](https://bazel.io/) integration.
