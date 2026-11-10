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

The CLJS spike workflow runs the three checks above on pull requests targeting
`main` and pushes to `main`. It uploads the command logs as a workflow artifact
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

Fingerprints are SHA-256 over the `candidate-v2` record containing the normalized
relative path, whole-source digest, location, top-level `:form-index`, operator, and exact edit. The
random UUIDs used by upstream discovery do not enter this identity. Applying a
candidate rediscovers and checks the complete candidate, rejecting changed
source or altered candidate fields. `resolve-candidate` returns exactly the
zipper at that indexed form and source offset; applying returns text and performs
no file write. The retained upstream `:form-id`/`:coord` pair is diagnostic and
can collide for repeated forms. Use the new resolver/apply API, not the upstream
JVM site's lookup, with these records. Candidate-v1 fingerprints from earlier
experiments must be retained with that earlier producer revision.

The current subset supports ordinary `.cljs`/`.cljc` forms, set literals,
anonymous functions, regex literals, and reader discard. Quoted data, comments,
namespace declarations, metadata, and recognized `def`/`defn` documentation and
attribute maps are excluded. Reader conditionals, tagged literals, namespaced
maps, var quote, and other unsupported dispatch forms return an explicit
`:parse-error`; the diagnostic identifies unsupported reader dispatch. An
unsupported file is never reported as a successful empty inventory. This is a
conservative source scanner, not a CLJS analyzer or coverage collector.

The spike's JVM alias pins Edamame 1.6.42 for reader validation without evaluation;
rewrite-clj retains the original source nodes for edits. Invalid literal maps and
sets are rejected before inventory creation. Namespace declarations supply the
aliases needed to validate auto-resolved keywords. Constant-expression operators
replace the enclosing comparison/and/or call; ordinary operators edit their
matched token. Both paths retain exact surrounding source text.
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
stream files. The CLI exits 0 for a valid survived/assertion-killed observation,
1 for a failed or invalid observation, and 2 for a CLI input error. An assertion
kill is an observed test outcome, never qualification credit.

The request timeout is the worker's async deadline. The parent timeout is a
separate process deadline and should allow time for process startup. A test
that blocks Node's event loop is stopped by the parent. The parent currently
terminates its direct child only and reports process-tree containment as
`:unknown`.

The worker first prints one `HERETIC_CLJS_RUNTIME` EDN line with the Node and nbb
versions observed inside that process. The parent records those values with
`:availability :observed` and `:provenance :worker-reported`; missing, malformed,
or duplicate headers are explicitly unavailable and prevent successful terminal
outcomes. A hard timeout retains an early header if the worker emitted one.

The worker loads the exact requested namespaces, waits for `cljs.test`'s
asynchronous end event, writes one terminal `HERETIC_CLJS_RESULT` EDN line,
then exits. Errors take precedence over assertion failures. Positive completed
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

Remaining adoption work includes broader reader support, dependency and async
compatibility, test selection with evidence of coverage, baseline binding beyond
test count, compiler-warning policy, descendant-process containment, and the
existing Axxium ordered-campaign/evidence contract. A warm Shadow compiler is a
separate execution option and must be compared with the same selected mutations
and tests before choosing the production backend.

See [the versioned spike contract](cljs-nbb-contract.md) for the request and
response fields and acceptance criteria.
