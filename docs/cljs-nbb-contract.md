# ClojureScript mutation spike contract v2

## User story

As an Axxium maintainer, I want Heretic to generate stable CLJS mutations and run selected tests in fresh nbb processes, so I can measure a faster execution path without changing qualification semantics.

## Scope and source

- Upstream Heretic: 45ed7c7deffd5fee1b3050efa7396d0113b30a4f.
- Axxium experiment source: 4a0a858a348527d661fcb434abbfaf01541be967.
- Development experiment only. Every new result has qualification-eligible? false.
- No changes to Axxium production source, frozen campaign selection, or accepted receipt policy.
- Preserve upstream licenses.

## Discovery boundary

Expose heretic.cljs.discovery/discover-source accepting a map:
{:path <normalized relative string> :source <UTF-8 source string>
 :operators <optional vector of upstream operator keyword IDs>}
Return:
{:status :ok :source-sha256 <64 hex> :candidates [candidate...]}
or {:status :parse-error :path ... :message ... :candidates []}.
A candidate includes :fingerprint, :path, :source-sha256, :operator, :original, :replacement, :line, :column, and the zero-based :form-index that identifies its top-level occurrence.
The upstream :form-id/:coord fields are retained as diagnostics. That pair alone is ambiguous for repeated forms and must not be passed to the upstream JVM mutation engine as an authoritative address.
Fingerprints are deterministic SHA-256 over the candidate-v2 canonical record, including :form-index, never a random upstream UUID. Historical candidate-v1 fingerprints are not interchangeable.
Expose resolve-candidate accepting {:source ... :candidate ...}; revalidate the entire candidate and return exactly the zipper at its indexed occurrence and source offset. Stale or altered locations are rejected.
Parse failures cannot look like empty successful inventories.
Validate supported source with a reader before discovering mutations, without evaluation. Odd maps, duplicate map keys, duplicate set members, malformed nested/discarded forms, and other reader failures are parse errors. Track namespace aliases for auto-resolved keywords rather than validating under an unrelated namespace.
Only live top-level namespace declarations update reader context, including declarations wrapped in metadata. Validate discarded forms without adopting their namespace declarations; resolution and matcher failures produce an explicit error rather than an incomplete inventory.
The first supported subset may deliberately reject reader-conditionals and tagged literals; unsupported source must be explicit, not silently scanned on the wrong branch.
Regex literals are restricted to the documented conservative subset; advanced patterns and unresolved auto-resolved keywords are explicitly unsupported, not claimed to be invalid in every CLJS runtime.
Quoted data, comments, reader-discard, ns declarations, metadata, and docstrings must not be treated as executable mutation sites.
Core form recognition is syntactic: it includes unqualified names, explicit `cljs.core`/`clojure.core` qualification, and declared aliases for those namespaces. It does not perform lexical binding or macro expansion analysis; similarly named forms in other namespaces remain ordinary calls.
Return exact original/replacement source representations and preserve the original file bytes outside the one edit.
Positions and edits must work with LF, CRLF, bare CR, and mixed line endings.
Keyword operators use the namespace and aliases in effect at each source form, including multiple namespace declarations. Original auto-resolved spelling is retained in :original; replacements may use explicit qualification. Rewrite-clj placeholder namespaces must never enter a replacement.
The four upstream constant-expression operators (:replace-comparison-false, :replace-comparison-true, :replace-and-false, :replace-or-true) replace the whole enclosing call when their matcher identifies its head. Non-head symbol occurrences are not eligible. A constant-expression edit must never create a call to true or false.
Expose apply-candidate accepting {:source ... :candidate ...}; return a new source string or reject stale source/candidate identity. Do not write the caller's source file.
Add a Clojure CLI only after the pure API works; it may read a file and print EDN.

## Worker request and response

Entry: scripts/nbb-runner.cljs REQUEST.edn.
Classpath is supplied to nbb by the parent.
Request:
{:schema :heretic.cljs/request-v1
 :test-namespaces [symbol ...]
 :timeout-ms 5000
 :expected-tests nil}
An explicit positive :expected-tests binds a mutated run to its baseline test count.
Reject invalid requests and duplicate namespace names.
Before loading requested tests, the worker emits one early line:
HERETIC_CLJS_RUNTIME {:schema :heretic.cljs/runtime-v1 :node-version <actual process version> :nbb-version <actual nbb runtime version>}
The parent retains those values under :execution/:runtime with :availability :observed and :provenance :worker-reported. Missing, malformed, or duplicate headers are explicitly :unavailable; successful terminal outcomes require one well-formed header. Runtime evidence is self-reported, not authentication of hostile test code.
The worker emits exactly one final line:
HERETIC_CLJS_RESULT <single EDN map>
Result:
{:schema :heretic.cljs/result-v1 :backend :nbb
 :status :survived|:assertion-killed|:test-error|:load-error|:timeout|:invalid-test-output
 :completed? boolean :tests integer :assertions integer :failures integer :errors integer
 :test-namespaces [symbol ...] :qualification-eligible? false}
Extra diagnostic fields are allowed; the parent must reject absent/duplicate/malformed result markers.
A completion record is emitted only after cljs.test's asynchronous end-run event.
A thrown/rejected test error dominates assertion failures.
A positive completed count with assertions, zero errors, matching expected test count, and failures > 0 is :assertion-killed; this remains exploratory and never qualification credit.
A positive completed count with assertions, zero errors, matching expected count, and zero failures is :survived.
Zero tests/assertions, count mismatch, duplicate completion, or malformed counters are invalid.
Load failure is separate from a test error.
Async timeout is incomplete. The parent enforces a separate hard process deadline for CPU loops.

## Required behavior tests before implementation

Discovery: repeatable IDs; exactly one changed expression; quotation/comment/data exclusions; malformed source and odd map literals; unsupported reader constructs; stale source rejection; repeated-form resolution and forged-index rejection.
Worker: sync pass; assertion failure; thrown error; mixed failure and error; async completion awaited; async never completes; missing namespace; zero tests; expected-count mismatch.
Isolation: execute at least two fresh workers and verify no fixture/global state carries over.
The parent records full stdout/stderr, command, observed runtime versions or explicit unavailability, exit, elapsed time, and timeout separately. The early runtime header can survive a later CPU-loop timeout. No process-tree containment guarantee is inferred from child exit.

## Integration and evidence

Use fixed input manifests and no mutation of the original Axxium checkout.
Keep detailed evidence outside the source commits.
Timing claims identify hardware/runtime, sample size, source/test subset, and cold/warm conditions.
A warm compiler experiment remains separate from the nbb backend.
No campaign completion, native producer adoption, or speedup is claimed without actual observations.
