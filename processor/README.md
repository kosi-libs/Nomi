# Nomi processors

Four Gradle modules implement the KSP processors described in `SPECS.md` §"Processors":

```
nomi-processor-common   analysis & validation only - not a runnable processor
    ↑
    ├── nomi-processor-shared   generates Nomi<Chain> resource impls + factory functions
    ├── nomi-processor-client   (not yet written)
    └── nomi-processor-server   (not yet written)
```

`nomi-processor-common` holds every verification rule from `SPECS.md` §"Verifications" that a
processor needs — currently the subset relevant to `@Endpoint.Resource` and `Stringer` declarations
(General, Resource declarations, Path parameter types, Resource paths, Stringers, and the
resource/factory half of Generated-name collisions). Per that section: *"each processor verifies
every declaration it reads, including ones from a dependency module — it never assumes a dependency
was already checked by whichever processor built it."* Rules about services, endpoint functions,
server implementations, and client factories belong here too, but are added only once
`nomi-processor-client`/`-server` are actually written and need them — there's no value in
validating declarations no processor yet reads.

## The reusable surface

- **`ResourceAnalyzer`** (`resource/ResourceAnalyzer.kt`) is the facade a processor calls:
  `analyze(declaration)` returns a validated `AnalyzedResource`, or `null` having already reported
  every violation — never a partially-built result. `analyzeAll` does the same for a batch.
- **`Reporter`** (`Reporter.kt`) is the seam that keeps every check testable without a live KSP
  round: checks report through `Reporter.error(at, message)` rather than calling `KSPLogger`
  directly. `LoggingReporter` adapts it to a real `KSPLogger`; `RecordingReporter` is the test double.
- **`resourcePath(simpleName, declaredPath, ownParameters)`** (`path/ResourcePathParser.kt`) is a
  *pure* function — no KSP import at all — that derives or parses a resource's path per
  SPECS.md §"Resource paths". Being pure, it's tested directly with `kotlin-test`, no compiler needed.
- **`StringerResolver.resolveFor(owner, type, stringed, scopes, user)`** (`stringer/StringerResolver.kt`)
  resolves a `Stringer` for any already-nullability-stripped `KSType`, given a nearest-first list of
  annotation scopes from `scopesOf(chain)`. It's written to be reusable for an endpoint query
  parameter later, not just a resource path parameter — the nullability-stripping and "is this the
  request body?" decisions are the *caller's* job, kept out of this function on purpose.
- **`NomiFq`** (`NomiFqNames.kt`) is the one place every Nomi annotation's fully-qualified name is
  written down as a string constant.

Each check function carries a one-line `// SPECS.md § <section>` comment naming the rule it
implements, rather than restating the rule in prose — the rule itself lives in exactly one place
(`SPECS.md`), so a spec change is a `grep` away from every place that implements it.

## Testing strategy — two lanes

1. **Pure logic**, in `nomi-processor-common`'s own `src/test`, using plain `kotlin-test-junit` —
   no KSP, no compiler, no `kctfork`. This covers anything with zero KSP dependency: path-string
   parsing (`ResourcePathParserTests`), the annotation-tree breadth-first walk
   (`AnnotationTreeWalkTests`), the nearest-wins Stringer resolution algorithm
   (`StringerResolutionTests`), and generated-name derivation (`GeneratedNameTests`).
2. **Real KSP rounds**, in `nomi-processor-shared`'s `src/test`, using `dev.zacsweers.kctfork` to
   compile fixture source through the actual processor and assert on `KotlinCompilation.ExitCode`
   and `diagnosticMessages`. This is where the *whole* validation rule set is exercised end-to-end —
   `nomi-processor-common` has no compiler test harness of its own, so this is deliberately the
   primary place these rules get proven correct, even though most of them live in the other module.

## Gotchas

- **Never use `KSAnnotated.getAnnotationsByType`/`isAnnotationPresent`.** They're `@KspExperimental`
  and reflectively load the annotation's `KClass`-typed arguments via `Class.forName` on the
  *processor's own classpath* — which will never have the user's types (`Uuid`, a user's `Stringer`
  object, …) on it, since the processor modules deliberately carry no runtime dependency. Read
  `KSAnnotation.arguments` directly instead (see `ksp/Annotations.kt`).
- **`KSClassDeclaration.superTypes` includes `kotlin.Any`**, even for an interface with no declared
  supertype at all. Every supertype-shape check filters it first (`declaredSupertypes()`).
- **KSP/kctfork version pin**: `kctfork:ksp:0.13.0`'s own POM requests the KSP2 implementation
  artifacts (`symbol-processing-aa-embeddable`, `-common-deps`) a version *older* than
  `symbol-processing-api` in the version catalog. `gradle/libs.versions.toml` pins
  `ksp-aaEmbeddable` to the same version as `ksp-symbolProcessingApi` to close that gap — carry this
  forward when `nomi-processor-client`/`-server` add their own `kctfork` test dependency.
- **Emitting `context(_: EndpointService)`**: use KotlinPoet's `FunSpec.Builder.contextParameter(ContextParameter(type))`
  (`@ExperimentalKotlinPoetApi`, `ContextParameter(type)` defaults its name to `_`) — never the
  deprecated `contextReceivers(...)`, which emits the old receiver-style syntax and is mutually
  exclusive with context parameters at `build()`.
- **A registration's own shape is validated at *discovery*, not at *use*.** `@Endpoint.UseStringer(type = X::class, with = Y::class)`
  is checked the moment it's found while walking a resource's annotation tree, regardless of
  whether any property in scope ends up matching it — SPECS.md's "not verified" list excuses an
  *unused* registration from scrutiny, not a structurally invalid one. Re-validating again at
  resolution time would just double-report the same problem.
- **User-declaration collision checks must compare file *paths*, not file *names*.** Once KSP starts
  a second round, its own previously-generated output becomes resolvable through the same
  `Resolver` calls used to detect collisions with hand-written user code. Comparing the colliding
  symbol's `containingFile.filePath` against the current round's own *source* file paths (not the
  generated-name string) is what tells the two apart.
