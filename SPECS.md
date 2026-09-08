# Nomi

## Rest API service builder

Nomi is a Kotlin library for building RESTful APIs, as well as server & client typed APIs.
It provides a set of annotations and utilities to simplify the process of creating and consuming RESTful APIs in Kotlin.

The user provides:
- The resources that define the API routes
- The services that define the API endpoints

Nomi then generates:
- The server-side code to implement the API
- The client-side code to consume the API

## Modules

Runtime (hand written, shipped to users):
- `runtime/nomi-runtime-shared`: multiplatform. Annotations, `Either`, `Error`/`NoFail`, `Stringer`, `LightException`.
- `runtime/nomi-runtime-client`: multiplatform. `ServiceCaller`, `CallStatus`, and the `@InternalNomiAPI` helpers the generated client calls.
- `runtime/nomi-runtime-server`: JVM only (ktor server). `httpError`, `routingCall`, and the `@InternalNomiAPI` helpers the generated routes call.

Processors (KSP):
- `processor/nomi-processor-common`: models, analysis, verification. Not a runnable processor.
- `processor/nomi-processor-shared`: generates the code shared between server & client.
- `processor/nomi-processor-server`: generates the server code.
- `processor/nomi-processor-client`: generates the client code.

All runtime code lives under the `org.kodein.rest.nomi` package (with `.client` and `.server` sub-packages for the
client- and server-only runtimes).

## User declaration API

Every declaration Nomi reads — resources, services, `Stringer` objects, server implementations, client
factory objects — may be `public` or `internal`. Whichever it is, the code the processors generate from
it carries the same visibility.

### Resources

The user declares routes by creating interfaces annotated with `@Endpoint.Resource`.

A resource interface is either:
- a **root** resource, declared at the top level, or nested only inside plain declarations (a class,
  object or interface that is not itself an `@Endpoint.Resource`) that act as a mere namespace and
  contribute nothing to its path: its own path is the whole prefix of its routes;
- a **nested** resource, declared inside another resource interface: it must extend the interface it is nested in,
  and its path is appended to that of its parent.

For each resource interface:
- The interface can contain properties that define the path parameters.
  A path parameter type must have a `Stringer` (see below).
- If the annotation specifies a path, that path must contain all of the interface's own path parameters (in `{}`).
  It must not repeat the parameters it inherits from its parent, which are already part of the parent's path.
- If the annotation does not specify a path, the path is built as the interface name (with a lowercase first letter),
  followed by the interface's own properties as path parameters, separated by `/`, in order of declaration.
- The interface can contain nested resource interfaces, which must also be annotated with `@Endpoint.Resource`,
  and must extend the outer interface.

For example, the current code:

```kotlin
@Endpoint.Resource("u") interface Users {
    @Endpoint.Resource interface New : Users
    @Endpoint.Resource interface User : Users {
        val userId: Uuid
        @Endpoint.Resource interface Posts : User {
            @Endpoint.Resource("p:{postId}") interface Post : Posts {
                val postId: Uuid
            }
        }
    }
}
```

defines the following paths:
- `/u`
- `/u/new`
- `/u/user/{userId}`
- `/u/user/{userId}/posts`
- `/u/user/{userId}/posts/p:{postId}`

Note that `User` gets its `user/` segment from its own interface name, because its annotation does not specify a path,
whereas `Post` gets no `post/` segment, because its annotation does specify one.

### Services

After declaring its app resources, the user declares the services that define the API endpoints that will use these resources.

A service is an interface annotated with `@Endpoint.Service`, and must extend the `EndpointService` marker interface,
directly or transitively. Its other supertypes may only be other `@Endpoint.Service` interfaces, or plain marker
interfaces that declare no members at all — an unannotated supertype that declares members (such as endpoint-shaped
methods) is an error. A service's endpoints are the union of its own methods and those of every `@Endpoint.Service`
interface it extends, however deep; a service may not itself `override` an inherited endpoint.

Each method in a service interface (own or inherited) defines an API endpoint:
- It must be `suspend`.
- It must be an extension function on a resource interface.
- When it is annotated with `@Endpoint`, that annotation's method is used, regardless of the method's name. When it
  is not annotated, its name must be one of `get`, `post`, `put`, `patch` or `delete`, and that name is the HTTP method.
- Its first parameter is the content of the request body if (and only if) it is not annotated with `@Endpoint.Query`.
  A request body is only allowed on `post`, `put` and `patch` endpoints.
- Any other parameter must be annotated with `@Endpoint.Query`. If the name of the query parameter is not defined in
  the annotation, the name of the parameter is used.
    - A nullable query parameter is optional: it is `null` when absent, and the client omits it from the URL when it
      is `null`. It may have a default value, but that is not required.
    - A non-nullable query parameter is required: the server answers `400 Bad Request` when it is absent.
- Its return type must be an `Either`, with the left `V` type being the type of a successful response (`Unit` if none),
  and the right `E` type being the type of a business failure (`NoFail` if none).

Two endpoints of the same service may not resolve to the same (path, HTTP method) pair.

Note: Standard HTTP errors (404 not found, 403 forbidden) should not be represented as "business failures".
A business failure is when the endpoint call failed for a business reason, not a resource reason (for example, because the client tried to create a user without a name).
A business failure type must be serializable by whichever converter the application registered (see "Wire protocol"
below). With kotlinx.serialization that means `@Serializable` — and, when the type is a sealed hierarchy, every one of
its members must carry `@Serializable` too, or it cannot be serialized polymorphically.

For example, the current code:

```kotlin
@Endpoint.Service
interface UsersService : EndpointService {
    suspend fun Users.get(@Endpoint.Query page: Int? = null): Either<List<UserSummary>, NoFail>
    @Endpoint(Endpoint.Method.Post) suspend fun Users.New.create(user: NewUser): Either<Uuid, UserCreationFailure>
    suspend fun Users.User.get(): Either<UserDetails, NoFail>
    suspend fun Users.User.delete(): Either<Unit, NoFail>
    suspend fun Users.User.Posts.get(@Endpoint.Query("p") page: Int? = null): Either<List<UserPost>, NoFail>
    suspend fun Users.User.Posts.post(content: NewPost): Either<Uuid, NoFail>
    suspend fun Users.User.Posts.Post.get(): Either<UserPost, NoFail>
    suspend fun Users.User.Posts.Post.delete(): Either<Unit, UserPostDeletionFailure>
}
```

defines the following endpoints:
```
- GET    /u?page
- POST   /u/new
- GET    /u/user/{userId}
- DELETE /u/user/{userId}
- GET    /u/user/{userId}/posts?p
- POST   /u/user/{userId}/posts
- GET    /u/user/{userId}/posts/p:{postId}
- DELETE /u/user/{userId}/posts/p:{postId}
```

#### Stringers

All resource path parameters and all service endpoint query arguments must be converted to `String` by the client, and parsed by the server.
That is the role of the `Stringer` interface.

Escaping is not a `Stringer`'s concern: the runtime percent-encodes every path segment and query value on its way out
and decodes it on the way back in, so a `Stringer` always encodes to, and decodes from, the *unescaped* text. A
`Stringer` that escapes its own output would have that output escaped a second time.

Built-in Stringers are provided for `Boolean`, `Int`, `Long` and `String` (the last a passthrough: the client sends
its value unchanged, and the server hands the received value back as it stands). They act as the outermost,
lowest-priority link of the resolution chain below: a project may register its own `Stringer` for one of these types
(with `@Endpoint.Stringed` or `@Endpoint.UseStringer`, exactly as for any other type) to override the built-in
encoding. For any other type, the user must provide a `Stringer` for the specific type.
To do so, the user must first create an object that implements the `Stringer` interface (it must be an `object`, neither a `class` nor an `interface` are valid).
Then, the user must associate the `Stringer` with the type it is used for.
The processor verifies that the registered object does implement `Stringer<T>` for the type it is registered for.
This can be done in multiple ways:
- By annotating the property or query parameter with `@Endpoint.Stringed`.
  ```kotlin
  @Endpoint.Resource interface User : Users {
      @Endpoint.Stringed(with = UuidStringer::class)
      val userId: Uuid
  }
  @Endpoint.Service interface UsersService : EndpointService {
    suspend fun Users.get(
        @Endpoint.Stringed(with = UuidStringer::class)
        @Endpoint.Query groupId: Uuid? = null
    ): Either<List<UserSummary>, NoFail>
  }
  ```
- By annotating the interface with `@Endpoint.UseStringer` (note that it is repeatable, so multiple Stringers may be registered to the same interface).
  ```kotlin
  @Endpoint.UseStringer(type = Uuid::class, with = UuidStringer::class)
  @Endpoint.Resource interface User : Users {
      val userId: Uuid
  }
  @Endpoint.UseStringer(type = Uuid::class, with = UuidStringer::class)
  @Endpoint.Service interface UsersService : EndpointService {
      suspend fun Users.get(@Endpoint.Query groupId: Uuid? = null): Either<List<UserSummary>, NoFail>
  }
  ```
- By annotating any parent interface with `@Endpoint.UseStringer`.
  ```kotlin
  @Endpoint.UseStringer(type = Uuid::class, with = UuidStringer::class)
  @Endpoint.Resource interface Users {
      @Endpoint.Resource interface User : Users {
          val userId: Uuid
      }
  }
  @Endpoint.Service
  @Endpoint.UseStringer(type = Uuid::class, with = UuidStringer::class)
  interface ServiceBase : EndpointService
  @Endpoint.Service interface UsersService : ServiceBase {
      suspend fun Users.get(@Endpoint.Query groupId: Uuid? = null): Either<List<UserSummary>, NoFail>
  }
  ```
- By creating a custom annotation, itself annotated with `@Endpoint.UseStringer`, and using that annotation on a resource interface (parent included).
  A carrier annotation may itself be annotated with other carrier annotations, to any depth — the whole annotation
  tree reachable from a declaration is walked, not just its direct annotations.
  ```kotlin
  @Retention(AnnotationRetention.BINARY)
  @Target(AnnotationTarget.CLASS)
  @Endpoint.UseStringer(type = Uuid::class, with = UuidStringer::class)
  annotation class UseAppStringers

  @UseAppStringers
  @Endpoint.Resource interface Users {
      @Endpoint.Resource interface User : Users {
          val userId: Uuid
      }
  }
  @UseAppStringers
  @Endpoint.Service interface UsersService : EndpointService {
      suspend fun Users.get(@Endpoint.Query groupId: Uuid? = null): Either<List<UserSummary>, NoFail>
  }
  ```

The nearest declaration wins, in this order:
1. `@Endpoint.Stringed` on the property or query parameter;
2. then, for the declaring interface and each interface outward — nearest first (for a resource, its chain of
   extended parent resources, then the plain declarations it is nested in; for a service, its chain of extended
   parent services) — the registrations reachable from *that* interface's own annotation tree, shallowest first:
   an `@Endpoint.UseStringer` written directly on it, then one reached through a carrier annotation applied to it,
   then one reached one level deeper, and so on;
3. the built-in `Stringer` for `Boolean`, `Int`, `Long` or `String`, as the final fallback.

Interface distance always dominates annotation depth: a registration on a nearer interface wins over one on a
farther interface, whatever their respective depths.

Two registrations for the same type found at the same interface distance and the same annotation depth are
ambiguous and are rejected **only when they name a different `Stringer`**; naming the same `Stringer` twice this way
(for instance, through a diamond of carrier annotations) is a harmless duplicate and is accepted.

### Wire protocol

Nomi defines the *shape* of a call — its method, its path, its query parameters, its status code, and whether a body
is present — and nothing about the *format* of that body.
Serialization is deliberately outside the scope of the library: Nomi never picks a media type, never registers a
converter, and never reads or writes a body itself. It hands the value to ktor and lets ktor's `ContentNegotiation`
plugin serialize it.

Choosing the format, and configuring it identically on both sides, is therefore the user's responsibility — JSON,
CBOR, ProtoBuf, or anything else ktor can negotiate. The examples throughout this document use JSON because the demo
does; nothing in Nomi assumes it.

- A successful call answers `200 OK`, with the serialized `V` value as its body.
- A successful call with nothing to return answers `204 No Content`, with no body — either because `V` is `Unit`,
  or because `V` is nullable and the value is `null`.
- A business failure answers `422 Unprocessable Entity`, with the serialized `E` error as its body.
  **`422` is reserved by Nomi**: `httpError` refuses it, and the client reads any other non-success status as an
  HTTP-level error rather than as a business failure.
- A path or query parameter that its `Stringer` fails to decode answers `400 Bad Request`, with the
  `Stringer.Failure` reason as its body. So does a missing required query parameter.
- Anything the service implementation raises through `httpError` answers with that status, and with the given message
  as its body when one is provided.

Path segments and query values are percent-encoded by the client and decoded by the server.
A query parameter carries a single value; repeated query parameters are not supported.

### Server implementation

The user then needs to provide an implementation of each of its services in their server code.
That implementation class must be annotated with `@ServerEndpointService`.

The server processor will generate ktor routes for that implementation.

```kotlin
@ServerEndpointService
class ServerUsersService : UsersService {
    /*...*/
}
```

Inside an endpoint implementation, the user can:
- return a business failure with `Either.error(...)`;
- abort with a standard HTTP status with `httpError(code, message)` (any code but `422`);
- reach the underlying ktor call with `routingCall()`.

### Client request

To request the client processor to generate a ktor client for a service, the user needs to annotate an `object` with `@ClientEndpointServiceFactory`.
The annotation is repeatable: one object may declare a factory for several services.
Its `name` argument is the name of the generated extension function.

```kotlin
@ClientEndpointServiceFactory(UsersService::class, "users")
object APIServices
```

## Verifications

Before generating any code, the processors verify that the `@Endpoint.*` annotations (and the declarations they
decorate) are used correctly. All rules below live in `nomi-processor-common`, so the three processors report
identical diagnostics; each processor verifies every declaration it reads, including ones from a dependency module —
it never assumes a dependency was already checked by whichever processor built it.

A violation is reported with `KSPLogger.error(message, symbol)` on the offending declaration or annotation argument.
A processor collects every violation in a round rather than stopping at the first, and generates nothing for that
round if any declaration it would need is invalid — never a partial file. A type that KSP cannot yet resolve (an
"error type") is deferred to the next round, and only reported as invalid if it is still unresolved when processing
finishes.

The rules below state the error condition, not the happy path; a bullet marked *(relaxable)* is a candidate for
loosening later, called out because it forbids something merely unneeded by the current examples, not something
unsound.

### General

- A declaration Nomi reads must be `public` or `internal`; `private`, `protected`, local and anonymous declarations
  are errors.
- A generated declaration is exactly as visible as the declaration it derives from; a declaration must not reference,
  anywhere the generated code reproduces it, a declaration less visible than itself (a public service must not use an
  internal resource, `Stringer`, parameter type or return type, and so on).
- No declaration Nomi reads may be `expect`/`actual`, `external`, `inner`, or local.
- No declaration Nomi reads may declare type parameters: resources, services, endpoint functions, server
  implementations and client factory objects are all required to be fully concrete.
- A `typealias` is resolved to its expansion and verified as that expansion.
- Every argument to a Nomi annotation must be a compile-time constant KSP can read.
- **Stray annotations should be errors** *(not yet implemented — needs a whole-module symbol sweep
  shared by all three processors)*: a processor collects every symbol carrying a Nomi annotation and
  rejects any it does not consume in that position: `@Endpoint` on a function that is not a service
  endpoint; `@Endpoint.Query` on a parameter of a non-endpoint function; `@Endpoint.Stringed` on
  anything other than a resource path parameter or an endpoint query parameter; `@Endpoint.UseStringer`
  on a class that is neither a resource, a service, nor an annotation class.
- A use-site target that moves a Nomi annotation off the declaration being read (`@get:Endpoint.Stringed`,
  `@field:Endpoint.Stringed`, …) is an error — reachable only for `@Endpoint.Stringed` on a resource
  property; a plain function parameter (an endpoint's body or query parameter) has no alternate
  use-site target to redirect an annotation to.

### Resource declarations (`@Endpoint.Resource`)

- Must be an `interface`. A class, object, annotation class, enum class, value class or `fun interface` is an error.
- Placement: a **root** resource is top-level, or nested only inside plain declarations (non-local, at least as
  visible as itself, and not themselves `@Endpoint.Resource`); a **nested** resource is declared directly inside the
  resource interface it extends.
- A nested resource's immediately enclosing resource interface must be its supertype directly — not reached
  indirectly through another interface, and not skipping a nesting level.
- A resource interface has no supertype other than that one; a root resource has none at all.
- A resource interface must not extend `EndpointService`, and must not also be annotated `@Endpoint.Service`.
- Every interface nested directly inside a resource interface must itself be annotated `@Endpoint.Resource`.
- No other nested declaration is allowed: no class, object, `companion object`, enum class, annotation class or
  typealias.
- No functions at all, including `suspend` ones, ones with a default body, `operator` functions, and explicit
  `toString`/`equals`/`hashCode` overrides — the shared processor's generated `data class`/`data object` supplies
  those.
- Only `val` properties are allowed; `var` is an error.
- Every property must be abstract: no initializer, no getter body, no delegate (`by`), and not `const` or `lateinit`.
- No extension properties, no property type parameters, and no `@JvmStatic`/`@JvmField`/`@JvmName` on a property.
- No property may `override` anything.
- A property name must not shadow one already declared by any ancestor in the resource's chain: they all become
  parameters of one generated class, and names in one ktor route.
- A property name must match `[A-Za-z_][A-Za-z0-9_]*` — no backticked identifiers, no `$`, no non-ASCII characters.

### Path parameter types

- A resource property's type must be non-nullable.
- Must not be a type parameter, a star projection, a functional type, an array, `Unit`, `Nothing` or `Any`.
- Must have a `Stringer` that resolves per the Stringers rules below.
- A parameterized type (`List<Int>`, say) can only be given a `Stringer` through `@Endpoint.Stringed` on the specific
  property, since `@Endpoint.UseStringer(type = …)` can only name a raw class, not a parameterized one.

### Resource paths

- An empty `path` is derived: the interface's simple name with a lowercase first letter, followed by one segment per
  own path parameter, in declaration order.
- When derived this way, the simple name must already be usable as a path segment as-is
  (`[A-Za-z][A-Za-z0-9_-]*`); an interface whose name does not fit must be given an explicit `path`.
- A non-empty `path` must not be blank, must not start or end with `/`, and must contain no empty segment (`//`).
- A `path` must contain none of `?`, `#`, `&`, `=`, whitespace, or a scheme/authority — it names path segments only.
- A `path` must contain no `%`: literal text is percent-encoded by the runtime on its way out, so a pre-encoded path
  would be encoded a second time.
- A `{}` placeholder must be balanced, must not be nested, and must name something non-empty.
- ktor's `*` wildcard, `{...}` tailcard, `{name...}` and `{name?}` syntaxes are all errors — Nomi routes are static.
- At most one placeholder per path segment, with an optional literal prefix and/or suffix in that same segment
  (`p:{postId}`, `{id}.json` are both fine); `{a}-{b}` in one segment is an error, since ktor cannot route it.
- Each of the resource's own path parameters must appear as a placeholder exactly once in its `path`; appearing zero
  or two-or-more times is an error.
- A placeholder naming anything other than one of the resource's own path parameters is an error — in particular, one
  naming an inherited path parameter, which is already part of the parent's path and must not be repeated.
- Every resource contributes at least one path segment; a resource whose derivation would produce zero segments
  cannot be expressed. *(relaxable)*

Note: two distinct resource interfaces are allowed to resolve to the same full path. Only two *endpoints* resolving
to the same `(path, method)` pair is an error (see Services below) — a bare path collision with no method conflict is
legal.

### Stringers

- The `with` argument (in `@Endpoint.Stringed` and `@Endpoint.UseStringer`) must name an `object` declaration —
  a named object or a `companion object` are both fine; a class, interface, abstract class, enum class, anonymous
  object or local object is an error.
- Must be non-local, and `public` or `internal`, at least as visible as every declaration that uses it.
- Must implement `Stringer<T>` for a concrete `T`; implementing raw `Stringer<*>`, or `Stringer<TypeParam>` through
  its own type parameter, is an error.
- `T` must be exactly the property's or parameter's type, nullability stripped for an optional query parameter —
  `Stringer<Number>` used for an `Int` property is an error, since `decode` must produce the declared type exactly.
- `@Endpoint.Stringed` on the request-body parameter of an endpoint is an error (the body has no `Stringer`; it is
  (de)serialized as a whole, per the Wire protocol section).
- `type` must name a class with no type parameters, and must not be `Unit`, `Nothing`, `Any`, an array, an annotation
  class or a functional type. Enum classes, value classes and ordinary classes are all fine.
- Two registrations for the same `type` reaching one declaration at the same interface distance and the same
  annotation depth are an ambiguity error only when they name a different `with`; naming the same `with` twice this
  way is a harmless duplicate and is accepted.
- Every annotation in a walked carrier-annotation chain must have `BINARY` or `RUNTIME` retention — a `SOURCE`
  annotation is absent from the class file, so it would resolve within its own module and silently fail to resolve
  from a dependent one. (A carrier meant to be applied to another annotation must also declare
  `AnnotationTarget.ANNOTATION_CLASS`, but Kotlin itself already rejects a misapplied annotation at its use site, so
  Nomi does not re-check applicability.)
- `@Endpoint.UseStringer` is collected by walking the full annotation tree reachable from the resource/service
  interface: its own annotations, the annotations on those annotation classes, and so on to any depth. The walk is
  breadth-first and visits each annotation class at most once, so it terminates on a cyclic annotation reference and
  collapses a diamond into a single visit; each registration is recorded at the shallowest depth it is found at.
- When no `Stringer` resolves for a type that needs one, the reported error names the type and the property or
  parameter that needed it.

### Service declarations (`@Endpoint.Service`)

- Must be an `interface`; not `sealed`, not `fun interface`.
- Must be top-level, or nested only inside plain declarations — never inside a resource interface.
- Must extend `EndpointService`, directly or transitively.
- Every other supertype must itself be `@Endpoint.Service`, or a plain interface declaring no members at all; an
  unannotated supertype that declares members (in particular, endpoint-shaped methods) is an error.
- Endpoints are collected from the whole `@Endpoint.Service` hierarchy: the interface's own methods, plus those of
  every `@Endpoint.Service` interface it extends, transitively.
- A service must not `override` an endpoint it inherits — doing so would register the same route twice.
- The only members allowed on a service interface are endpoint functions: no properties, no nested types, no
  `companion object`, no typealias.
- No two endpoints of one service — own or inherited — may resolve to the same `(path, method)` pair.
- A service declaring zero endpoints is legal (for instance, a base interface that exists only to carry
  `@Endpoint.UseStringer` for the interfaces that extend it).
- A service interface must not also be annotated `@Endpoint.Resource`.

### Endpoint functions

- Must be `suspend`.
- Must be abstract; a default body on an endpoint method is an error.
- Must have an extension receiver, and that receiver must be a non-nullable `@Endpoint.Resource` interface type with
  no type arguments.
- Must declare no type parameters, and must not be `inline`, `infix`, `operator`, `external` or `tailrec`; no
  `@JvmName`/`@JvmStatic`.
- Its HTTP method comes from `@Endpoint(method)` when present — the annotation always wins, even over a name that
  looks like a different verb. Without `@Endpoint`, the function's name must be exactly one of `get`, `post`, `put`,
  `patch` or `delete`, lowercase; any other name without `@Endpoint` is an error.
- The function name must be a plain identifier — no backticked name. *(relaxable)*
- The receiver resource's generated implementation must be available: either the resource is declared in the module
  under compilation, or its `Nomi…` implementation resolves from a dependency whose shared processor already ran.
  Otherwise, the error names the resource whose generated implementation is missing.

### Endpoint parameters

- Every parameter is either the request body (must be first, and unannotated) or a query parameter (annotated
  `@Endpoint.Query`). An unannotated parameter anywhere but first is an error.
- A body parameter is allowed only when the HTTP method is `Post`, `Put` or `Patch`; one on `Get` or `Delete` is an
  error.
- The body parameter must not be `vararg`, must have no default value (the server always reads it from the request,
  so a default could never apply), and must be non-nullable.
- The body parameter's type must not be `Unit`, `Nothing`, a functional type or a type parameter.
- The body parameter must carry neither `@Endpoint.Stringed` nor `@Endpoint.Query`.
- A query parameter must not be `vararg`.
- A **non-nullable** (required) query parameter must not have a default value: the server answers `400 Bad Request`
  when it is absent, so a default could never be reached.
- A **nullable** (optional) query parameter may have any default value, including a non-`null` one — defaults apply
  only at the call site, on the client; the server always passes either the decoded value or `null` explicitly. This
  is documented behavior, not an error.
- A query parameter's type must have a `Stringer` that resolves (nullability stripped for the purpose of resolution);
  it must not be `Unit`, `Nothing`, `Any`, a functional type, a type parameter or an array.
- The effective query parameter name — `@Endpoint.Query(name)`'s argument, or the parameter's own name when that
  argument is empty — must be non-blank and match `[A-Za-z_][A-Za-z0-9_.-]*` (no `&`, `=`, `?`, `#`, `%`, `+`, or
  whitespace).
- Two query parameters of the same endpoint must not resolve to the same effective name.
- A query parameter's effective name must not collide with the name of any path parameter along the receiver's
  resource chain: ktor's `RoutingCall.parameters` merges path and query parameters, so such a collision would make
  the value read at that name ambiguous.
- Every endpoint parameter name must be a plain identifier, since it is reproduced as a named argument in generated
  code.

### Endpoint return types

- Must resolve to `org.kodein.rest.nomi.Either<V, E>` — written directly, or through a `typealias` that expands to
  it (per the general typealias rule above), including a parameterized one such as `typealias Ok<V> = Either<V,
  NoFail>` used as `Ok<UserDetails>`.
- Every rule below applies to the expansion: it must not be a supertype of `Either`, must not be nullable (a
  `typealias` onto a nullable `Either` is still an error), must carry no star projection, and both type arguments
  must be concrete after expansion — never a type parameter.
- `E` must be a concrete, non-nullable type implementing `Error`; `Error` itself, and `Nothing`, are both errors.
- `V` must not be `Nothing`, `Any`, `Any?` or `Unit?`.
- **`V` and `E` must be disjoint**: neither may be a subtype of the other, and they must not be the same type.
  `Either` stores one value and discriminates between success and failure with `isInstance` (see `Either.kt`); any
  overlap between `V` and `E` would make a failure indistinguishable from a value at runtime.
- `V` may be `Unit`, or nullable — both cases answer `204 No Content` (see Wire protocol).
- `NoFail` must not be used as `V`.

### Server implementations (`@ServerEndpointService`)

- Must be a concrete `class` or `object`; an interface, abstract class, sealed class, annotation class or enum class
  is an error.
- Must not be `inner`, local or anonymous; must be `public` or `internal`; must be top-level or nested only inside
  plain declarations.
- Must implement at least one `@Endpoint.Service` interface, directly or transitively.
- Across *all* the services it implements together, no two endpoints may resolve to the same `(path, method)` pair —
  they would all be registered on the one `Route` this class's generated function builds.
- Every endpoint it must implement needs its receiver resource's implementation resolvable (see Endpoint functions
  above) and every `Stringer` it needs resolvable (see Stringers above).

### Client factories (`@ClientEndpointServiceFactory`)

- Must annotate an `object` declaration; a class, interface, annotation class or enum class is an error.
- Must not be a `companion object`, a local object or an anonymous object *(relaxable)*; must be `public` or
  `internal`; must be top-level or nested only inside plain declarations.
- `serviceClass` must name an interface annotated `@Endpoint.Service` — the type system alone only guarantees it
  extends `EndpointService`.
- `name` must be non-blank, match `[A-Za-z_][A-Za-z0-9_]*`, and must not be a Kotlin hard keyword.
- On one annotated object, `serviceClass` must not repeat, and no two of its `@ClientEndpointServiceFactory`
  annotations may share a `name`.
- The generated `<Object>.<name>` extension function must not collide with an existing member or extension of that
  object, nor with another generated extension of it.
- Every endpoint of the named service must itself be generatable: its receiver resource's implementation must
  resolve, and every `Stringer` it needs must resolve.

### Generated-name collisions

- A resource's generated implementation is named `Nomi`, followed by the simple names of the resource interface and
  of every interface or plain declaration it is nested in, outermost first. Two resources in the same package must
  not produce the same generated name this way (for example a top-level `Users` with a nested `New`, next to an
  unrelated top-level `UsersNew`) — the error names both.
- A resource's generated factory function is top-level for a root resource, or an extension of the parent resource
  for a nested one. No two generated factory functions may collide on (receiver, name).
- A client implementation, named `Nomi<Object><Service>Client`, must be unique per package.
- Generated file names must be unique per package.
- A generated declaration must not collide with a resolvable user declaration in the same package.

### Deliberately not verified

- **Serializability** of `V`, `E`, or a request body type. Serialization is outside Nomi's scope (see Wire protocol);
  a type missing `@Serializable` (or a sealed hierarchy member missing it) surfaces from the serialization plugin
  itself, or as a runtime failure — not from a Nomi processor.
- Client and server **configuration** (`ContentNegotiation`, the base URL, the `Content-Type`) is invisible to a
  processor and is reported at runtime instead (see Client request).
- Whether a `Stringer` round-trips (`decode(encode(v)) == v`), or wrongly escapes its own output.
- Whether a blanket `@Endpoint.UseStringer` registration is ever actually used by anything in scope.
- An `(path, method)` collision between two services that are never implemented together by the same
  `@ServerEndpointService` class, or between two such classes mounted on the same ktor `Route` at runtime — neither
  is visible to a processor.
- Two distinct resources resolving to the same path (explicitly allowed — see Resource paths above).

## Processors

### Shared processor

For each `@Endpoint.Resource` interface, the shared processor generates a public implementation of that interface,
annotated with `@InternalNomiAPI`, whose name is the concatenation of the simple names of the interface and of all of
its enclosing resource interfaces, prefixed with `Nomi`.
It is a `data object` when the resource has no path parameter at all, and a `data class` otherwise, holding its own
path parameters as well as every path parameter it inherits from its parents.

It also generates an associated factory function that allows the user to create the data object or class by emulating
the nested resource route: a top-level function for a root resource, an extension function on the parent resource for
a nested one. That function takes only the resource's own path parameters — the inherited ones are read from the
receiver.

Example:
```kotlin
@InternalNomiAPI public data object NomiUsers : Users
@InternalNomiAPI public data object NomiUsersNew : Users.New
@InternalNomiAPI public data class NomiUsersUser(override val userId: Uuid) : Users.User
@InternalNomiAPI public data class NomiUsersUserPosts(override val userId: Uuid) : Users.User.Posts
@InternalNomiAPI public data class NomiUsersUserPostsPost(
    override val userId: Uuid,
    override val postId: Uuid,
) : Users.User.Posts.Post

@OptIn(InternalNomiAPI::class)
context(_: EndpointService)
public fun Users(): Users = NomiUsers

@OptIn(InternalNomiAPI::class)
context(_: EndpointService)
public fun Users.New(): Users.New = NomiUsersNew

@OptIn(InternalNomiAPI::class)
context(_: EndpointService)
public fun Users.User(userId: Uuid): Users.User = NomiUsersUser(userId)

@OptIn(InternalNomiAPI::class)
context(_: EndpointService)
public fun Users.User.Posts(): Users.User.Posts = NomiUsersUserPosts(userId)

@OptIn(InternalNomiAPI::class)
context(_: EndpointService)
public fun Users.User.Posts.Post(postId: Uuid): Users.User.Posts.Post = NomiUsersUserPostsPost(userId, postId)
```

The `context(_: EndpointService)` context parameter ensures that a resource can only be built inside a service call.

### Server processor

For each `@ServerEndpointService` annotated class, the server processor generates, in the same package, a `fun Route.service(service: <ServiceImplementation>)` function.
That function registers all endpoints declared in all `@Endpoint.Service` interfaces this class implements.

For each endpoint, the function generates a nested ktor route and calls the associated service method with:
- The resource object implementation generated by the shared processor.
- An input parameter received from ktor (if it has an input)
- Every query parameter the endpoint declares.

All of the HTTP method handlers of a given path are siblings inside that path's `route { }` block: a handler block
(`get { }`, `delete { }`, …) must never contain another handler declaration, as that would register the inner route
lazily — and repeatedly — while serving the outer one.

Example:
```kotlin
@OptIn(InternalNomiAPI::class)
fun Route.service(service: ServerUsersService) {
    route("/u") {
        get {
            call.respond(service) {
                NomiUsers
                    .get(
                        page = decodeOrNull(IntStringer, call.queryParameters["page"])
                    )
            }
        }
        route("/new") {
            post {
                call.respond(service) {
                    NomiUsersNew
                        .create(
                            user = call.receive()
                        )
                }
            }
        }
        route("/user/{userId}") {
            get {
                call.respond(service) {
                    NomiUsersUser(
                        userId = decode(UuidStringer, call.parameters["userId"]!!)
                    )
                        .get()
                }
            }
            delete {
                call.respond(service) {
                    NomiUsersUser(
                        userId = decode(UuidStringer, call.parameters["userId"]!!)
                    )
                        .delete()
                }
            }
          // ...
        }
    }
}
```
Note that `decode`, `decodeOrNull`, `decodeRequired` and `call.respond` are public functions provided by the runtime,
under the `@InternalNomiAPI` optin annotation:
- `decode(stringer, value: String)` decodes a path parameter, and raises `400 Bad Request` when the `Stringer` fails.
- `decodeOrNull(stringer, value: String?)` decodes an optional query parameter, and returns `null` when it is absent.
- `decodeRequired(stringer, name, value: String?)` decodes a required query parameter, and raises `400 Bad Request`
  when it is absent.

#### Usage

The user can use the generated route function with its own service implementation as such:
```kotlin
embeddedServer(
    factory = CIO,
    port = 8080,
) {
    install(ContentNegotiation) {
        json(SharedJson)
    }
    routing {
        service(ServerUsersService())
    }
}.start(wait = true)
```

`ContentNegotiation` is not optional: without a converter registered for the format the client sends, the server
cannot read a request body and cannot serialize a response. The `json(SharedJson)` above is the demo's choice —
substitute `cbor(…)` or any other ktor converter, as long as the client is configured to match.

### Client processor

For each `@ClientEndpointServiceFactory` annotated object, the client processor generates, in the same package:
- A private implementation of the service interface that also implements `NomiClientEndpointService`.
- A public extension function on the annotated object, named after the annotation's `name` argument,
  that creates a `ServiceCaller` for the service interface.

```kotlin
@OptIn(InternalNomiAPI::class)
private class NomiAPIServicesUsersServiceClient(
    val client: HttpClient,
    override val buildRequest: HttpRequestBuilder.() -> Unit
) : UsersService, NomiClientEndpointService {
    override suspend fun Users.get(page: Int?): Either<List<UserSummary>, NoFail> =
        client.call(
            pathSegments = listOf(
                "u"
            ),
            method = Get,
        ) {
            appendParameter("page", page, IntStringer)
        }

    override suspend fun Users.New.create(user: NewUser): Either<Uuid, UserCreationFailure> =
        client.call(
            pathSegments = listOf(
                "u",
                "new",
            ),
            method = Post,
        ) {
            setBody(user)
        }

    override suspend fun Users.User.get(): Either<UserDetails, NoFail> =
        client.call(
            pathSegments = listOf(
                "u",
                "user",
                UuidStringer.encode(userId),
            ),
            method = Get,
        )

    //...
}

@OptIn(InternalNomiAPI::class)
public fun APIServices.users(client: HttpClient): ServiceCaller<UsersService> =
    NomiServiceCaller(
        client = client,
        service = ::NomiAPIServicesUsersServiceClient,
    )
```

A resource path that mixes literal text and a parameter inside a single segment (such as `p:{postId}`) is rendered as
the concatenation of that literal text and the encoded parameter, in a single path segment.

#### Usage

The generated extension function takes the `HttpClient` the calls will run on. Nomi does not create or configure that
client, so the application must set up three things on it:

```kotlin
val client = HttpClient {
    install(ContentNegotiation) {
        json(SharedJson)
    }
    defaultRequest {
        url("https://api.example.com/")
        contentType(ContentType.Application.Json)
    }
}
```

- **`ContentNegotiation`, with the same format the server registered.** Without it nothing can be serialized: a
  request carrying a body fails while being prepared, and a response body fails to be read.
- **A base URL, in `defaultRequest`.** A call contributes only its own path segments, which are appended to this one.
  *The base URL must end in a slash* if it carries a path: this is ordinary URL resolution, so
  `https://api.example.com/v1` treats `v1` as a resource to be replaced and the prefix is silently lost, whereas
  `https://api.example.com/v1/` keeps it.
- **A `Content-Type`, in `defaultRequest`.** `ContentNegotiation` chooses its converter from the request's content
  type, so with none set it cannot serialize a request body, and every `post`, `put` and `patch` fails before
  reaching the network.

Get any of this wrong and the call **throws**, rather than returning a `CallStatus`. A misconfigured client is a
programming error, not a call outcome, so Nomi lets ktor's own exception through with its own diagnostic rather than
flattening it into a `NetworkFailure` that would be indistinguishable from a server being down:

| Mistake | What is raised |
| --- | --- |
| No `ContentNegotiation`, or no `Content-Type`, on a request with a body | `IllegalStateException`, from ktor: _"Fail to prepare request body for sending… with Content-Type: null"_ |
| No `ContentNegotiation`, or a format the response does not match, when reading a response body | `NoTransformationFoundException`, from ktor: _"Expected response body of the type … but was …"_ |

Anything else the calls need — authentication, logging, timeouts, an engine — is configured on the same client, or
per call through `buildRequest` below.

It returns a `ServiceCaller`, which runs one call and reports its outcome as a `CallStatus.Result`:

```kotlin
val users = APIServices.users(client)

val result = users.call { Users().get(page = 0) }
```

The `call` block runs with the service as its receiver, so that resource factories and endpoint methods are in
scope. It also runs with a `ServiceCaller.CallBuilder` context, whose `buildRequest { }` configures the underlying
ktor request (headers, authentication…) for that call.

`CallStatus.Result` is one of:
- `Success(value)`: the endpoint answered `200 OK`.
- `CallFailure(error)`: the endpoint answered `422 Unprocessable Entity` with a business failure.
- `ServerFailure(code, body)`: the server answered any other non-success status.
- `NetworkFailure(message, cause)`: the request never reached the server.

`ServiceCaller.call` is responsible for that mapping: `client.call` raises `EndpointStatusCodeException` and
`EndpointNetworkException`, which `ServiceCaller.call` catches and turns into `ServerFailure` and `NetworkFailure`
respectively. No Nomi exception ever escapes it — but not everything raised during a call is a Nomi exception.

A `CallStatus` describes how a call *went*, so only things that can happen to a well-formed call are reported that
way. Two kinds of throwable are deliberately let through instead:

- **Coroutine cancellation.** A `CancellationException` raised while a call is in flight propagates to the caller
  unchanged. Reporting it as a `NetworkFailure` would let the caller carry on after its scope had been cancelled.
- **Configuration errors.** The exceptions in the client-configuration table above propagate unchanged too. They say
  the client was built wrong, which no amount of retrying or error handling will fix, and their own messages name the
  cause precisely — something a `NetworkFailure` could not do.

Everything else that goes wrong during a call becomes a `CallStatus.Result`.

`CallStatus` additionally carries a `Loading` state, so that the same type can model a call still in flight in a UI.
