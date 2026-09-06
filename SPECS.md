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

### Resources

The user declares routes by creating interfaces annotated with `@Endpoint.Resource`.

A resource interface is either:
- a **root** resource, declared at the top level: its path is the whole prefix of its routes;
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

A service is an interface annotated with `@Endpoint.Service`, and must extend the `EndpointService` marker interface.
Each method in a service interface defines an API endpoint:
- It must be `suspend`.
- It must be an extension function on a resource interface.
- Its name must be an HTTP verb (amongst `get`, `post`, `put`, `patch` or `delete`), or be annotated with `@Endpoint` and define the http method.
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
A business failure type must be `@Serializable` — and, when it is a sealed hierarchy, every one of its members must be
`@Serializable` too, otherwise it cannot be serialized polymorphically.

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
Built-in Stringers are provided for `Boolean`, `Int` and `Long`.
For any other type, the user must provide a `Stringer` for the specific type.
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

The nearest declaration wins: `@Endpoint.Stringed` on the property or parameter overrides an `@Endpoint.UseStringer`
on the declaring interface, which overrides one on a parent interface.

### Wire protocol

- A successful call answers `200 OK`, with the serialized `V` value as its body, or with no body at all when `V` is `Unit`.
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

The generated extension function returns a `ServiceCaller`, which runs one call and reports its outcome as a
`CallStatus.Result`:

```kotlin
val users = APIServices.users(client)

val result = users.execute { Users().get(page = 0) }
```

The `execute` block runs with the service as its receiver, so that resource factories and endpoint methods are in
scope. It also runs with a `ServiceCaller.CallBuilder` context, whose `buildRequest { }` configures the underlying
ktor request (headers, authentication…) for that call.

`CallStatus.Result` is one of:
- `Success(value)`: the endpoint answered `200 OK`.
- `CallFailure(error)`: the endpoint answered `422 Unprocessable Entity` with a business failure.
- `ServerFailure(code)`: the server answered any other non-success status.
- `NetworkFailure`: the request never reached the server.

`ServiceCaller.execute` is responsible for that mapping: `client.call` raises `EndpointStatusCodeException` and
`EndpointNetworkException`, which `execute` catches and turns into `ServerFailure` and `NetworkFailure` respectively.
No Nomi exception ever escapes `execute`.

`CallStatus` additionally carries a `Loading` state, so that the same type can model a call still in flight in a UI.
