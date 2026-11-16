# Experimental ClojureScript backend

This branch adds a small development backend to Heretic: deterministic source
mutations, a fresh nbb worker, and a JVM parent that validates the worker's
result and enforces a hard deadline. It is an implementation spike for Axxium.
Every result carries `:qualification-eligible? false`.

The existing JVM/ClojureStorm engine is unchanged. This backend does not replace
Axxium's qualification campaign, candidate order, selection profile, test
coverage requirements, or accepted receipt policy.

## Run the behavior checks

Use Node **22.23.3**, Clojure CLI, and Java 21. The npm lockfile pins nbb
**1.4.207**; the `:cljs-spike` alias selects a small JVM dependency set without
requiring ClojureStorm.

```sh
npm ci
npm run test:cljs
clojure -M:cljs-spike -m heretic.cljs.test-runner
```

Run these commands from the repository root. `HERETIC_NODE` and
`HERETIC_NBB_CLI` can select explicit executable/module paths for JVM process
tests. `HERETIC_PROJECT_DIR` can point those tests at this checkout when launched
from another directory.

The tests cover source identity, exact single edits, quotation and documentation
exclusions, unsupported reader syntax, synchronous and asynchronous test
outcomes, missing tests, malformed output, fresh-process isolation, and the
parent's deadline for non-yielding processes. Test logs are temporary local
outputs; production calls retain their stdout/stderr files in the requested
log directory.

## Continuous integration

The CLJS spike workflow runs the three checks above on pull requests and on
pushes to `main`. It uploads the command logs as a workflow artifact
for 14 days. These checks cover the experimental backend; they do not run the
full upstream suite or establish qualification eligibility.

## Discover and apply a mutation

```sh
clojure -M:cljs-spike -m heretic.cljs.cli discover test/fixtures/cljs_runner/fixture/pass.cljs
```

The CLI prints EDN. The underlying pure API accepts source text, so callers can
build an immutable inventory before creating any mutated file:

```clojure
(require '[heretic.cljs.discovery :as discovery])

(def source "(ns example)\n(defn add [x] (+ x 1))\n")
(def inventory
  (discovery/discover-source
    {:path "src/example.cljs"
     :source source
     :operators [:swap-plus-minus]}))

(def candidate (first (:candidates inventory)))
(def changed
  (discovery/apply-candidate {:source source :candidate candidate}))
```

Fingerprints are SHA-256 of one canonical printed vector:

```clojure
[:heretic.cljs/candidate-v2
 path source-sha256 operator original replacement
 line column form-id coord form-index offset]
```

Every field in that vector is part of the identity, including `:form-id`,
`:coord`, and `:offset`. `:form-id` is the Clojure `hash` of the top-level form
text. `:coord` is the upstream coordinate mapper's string. A change to either
algorithm changes every fingerprint without a version bump, so both are
identity, not diagnostics. The `:form-id`/`:coord` pair alone can still collide
for repeated forms and must not be passed to the upstream JVM mutation engine
as an authoritative address. Use the fingerprint together with
`resolve-candidate` / `apply-candidate`. Printing binds `*print-length*` and
`*print-level*` to nil, `*print-readably*` to true, `*print-dup*` and
`*print-meta*` to false, and `*print-namespace-maps*` to false, so the digest
and a string replacement do not follow the caller's printer settings. The
random UUIDs used by upstream discovery do not enter this identity. Applying a
candidate rediscovers and checks the complete candidate, rejecting changed
source or altered candidate fields. `resolve-candidate` returns exactly the
zipper at that indexed form and source offset; applying returns text and performs
no file write. Candidate-v1 fingerprints from earlier experiments must be
retained with that earlier producer revision.

The current subset supports ordinary `.cljs`/`.cljc` forms, set literals,
anonymous functions, regex literals, reader discard, `#js` maps and vectors,
`#inst`, `#uuid`, reader conditionals `#?` and `#?@`, and the symbolic numbers
`##Inf`, `##-Inf`, and `##NaN`. Quoted data, comments, namespace declarations,
metadata, and recognized `def`/`defn` documentation and attribute maps are
excluded. `#js` payloads are walked because the ClojureScript compiler emits
their values as expressions. `#inst`, `#uuid`, and reader conditionals are
parsed and then left unmutated, so a conditional's inactive branch is not an
edit. Namespaced maps, var quote, `#=`, and other tags return an explicit
`:parse-error`; the diagnostic identifies unsupported reader dispatch. An
unsupported file is never reported as a successful empty inventory. This is a
conservative source scanner, not a CLJS analyzer or coverage collector.

The spike's JVM alias pins Edamame 1.6.42 for reader validation without evaluation;
rewrite-clj retains the original source nodes for edits. Invalid literal maps and
sets are rejected before inventory creation. Namespace declarations supply the
aliases needed to validate auto-resolved keywords. Constant-expression operators
replace the enclosing comparison/and/or call; ordinary operators edit their
matched token. A constant-expression call is skipped when that call is the head
of its parent, or when the parent is headed by a core threading form (`->`,
`->>`, `some->`, `some->>`, `cond->`, `cond->>`, `doto`, `..`), including
`cljs.core`/`clojure.core` qualification and aliases of those namespaces.
Those positions would turn `true` or `false` into the operator of a call.
Both paths retain exact surrounding source text. A `#` inside a symbol,
including a syntax-quoted auto-gensym, is not reader dispatch.
Keyword matching and transformations use the namespace/aliases active at that
form. Auto-resolved originals retain their source spelling; replacements can be
explicitly qualified to preserve the intended semantic transformation.

Regex support is deliberately conservative: ASCII literals, common character
classes, grouping/noncapturing groups, anchors and ordinary quantifiers, selected
escapes (`d`, `D`, `s`, `S`, `w`, `W`, `.`, `/`, `\\`, `-`), and an initial
`(?i)` flag. Advanced `(?...)` constructs, repeated quantifiers, POSIX class
syntax and other escapes return an explicit unsupported-syntax parse error.
An auto-resolved keyword without a declared namespace/known alias is also
unsupported. This restriction does not claim that such source is invalid in all
CLJS runtimes.

## Execute a worker through the parent

Save this example as `worker-options.edn` in the repository root:

```edn
{:command ["node" "node_modules/nbb/cli.js"
           "-cp" "src:test/fixtures/cljs_runner"
           "scripts/nbb-runner.cljs"]
 :directory "."
 :timeout-ms 5000
 :log-dir ".heretic-cljs/example"
 :request {:schema :heretic.cljs/request-v1
           :test-namespaces [fixture.pass]
           :timeout-ms 1000
           :expected-tests 1}}
```

Then run:

```sh
clojure -M:cljs-spike -m heretic.cljs.cli run worker-options.edn
```

The parent writes the request file and appends its absolute path to `:command`.
The response contains `:execution` (command, paths, stdout, stderr, exit,
duration, deadline information, runtime evidence) and `:result` (the validated test outcome).
`:log-dir` is created as needed; each invocation has separate request and
stream files. Each captured stream is read only when its file is at most 1 MiB.
A larger file is left on disk and the result is `:invalid-test-output` with
runtime availability `:unavailable` and reason `:overflow`; the unread text is
not parsed. The CLI exits 0 for a valid survived/assertion-killed observation,
1 for a failed or invalid observation, and 2 for a CLI input error. An assertion
kill is an observed test outcome, never qualification credit.

The request timeout is the worker's async deadline. The parent timeout is a
separate process deadline and should allow time for process startup. A test
that blocks Node's event loop is stopped by the parent. The parent signals live descendants of its direct child and then the child.
`:containment` is `:direct-child` when no descendant was visible,
`:descendants-signaled` when at least one was, and `:unknown` when the tree
could not be inspected or the worker was never started. A descendant that the
child has already reparented is outside this signal. This is not a sandbox.

The worker first prints one `HERETIC_CLJS_RUNTIME` EDN line with the Node and nbb
versions observed inside that process. The parent records those values with
`:availability :observed` and `:provenance :worker-reported`; missing, malformed,
or duplicate headers are explicitly unavailable and prevent successful terminal
outcomes. A hard timeout retains an early header if the worker emitted one.

The worker loads the exact requested namespaces, waits for `cljs.test`'s
asynchronous end event, writes one terminal `HERETIC_CLJS_RESULT` EDN line,
then exits. Completion events emitted while those namespaces are loading are
ignored. The worker reinstalls its `:end-run-tests` handler after loading and
immediately before the requested run, so a namespace `defmethod` for that event
cannot drop the completion callback. Errors take precedence over assertion failures. Positive completed
test and assertion counts and baseline test-count agreement are required for
`:survived` or `:assertion-killed`. Load errors, timeouts, and invalid output are
separate outcomes. The parent rejects missing, duplicate, nonfinal, malformed,
or internally inconsistent result markers, nonzero child exits, and results
from a process that exceeded its deadline.

Structural validation cannot authenticate fabricated counters from a malicious
test process. Duplicate completion notifications can be observed before the
one-shot worker exits; later callbacks cannot be inspected after termination.

## Axxium integration boundary

An Axxium caller must supply its source/test classpath and compatible dependency
sources to nbb. nbb uses an interpreter; a namespace being pure does not prove
that its macros, libraries, or compiler features are supported.

The initial Axxium experiment uses revision
`4a0a858a348527d661fcb434abbfaf01541be967` and the exact test namespaces
`axxium.law.device-polling-test`, `axxium.law.identity-test`, and
`axxium.domain.resource-test`. The exploratory mutation sample is separate from
the frozen standard-v2 qualification profile. Details and all timing evidence
belong in the experiment artifacts, outside source commits.

`#js`, `#inst`, `#uuid`, reader conditionals, and `##Inf`/`##-Inf`/`##NaN` are
now accepted by discovery. `heretic.cljs.compatibility/scan` classifies a
checkout, and `heretic.cljs.evidence/assemble` emits a deterministic record.
The eligibility rule and the production-admission law are in
[the evidence note](cljs-evidence.md). Still outside this backend: nbb loading
of HoneySQL, Closure `goog.math.Long`, bare `await`/`Fastify`, a packaged
compiler, and Axxium Services admission. A warm Shadow compiler remains a
separate execution option.

See [the versioned spike contract](cljs-nbb-contract.md) for the request and
response fields and acceptance criteria.
