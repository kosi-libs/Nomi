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

## User declaration API

### Resources

The user declares routes by creating interfaces annotated with `@Endpoint.Resource`.

For each resource interface:
- If the annotation does not specify a path, the interface name is used as the path (with a lowercase first letter).
- The interface can contain properties that define the route parameters.
- If the annotation contains a path, it must contain all path parameters (in `{}`).
- If the annotation does not contain a path, properties are converted to path parameters, separated by a `/`, in order of declaration.
- The interface can contain nested resource interfaces, which must also be annotated with `@Endpoint.Resource`, and must extend the outer interface.

For example, the current code:

```kotlin
@Endpoint.Resource("u") interface Users {
    @Endpoint.Resource interface New : Users
    @Endpoint.Resource interface User : Users {
        val userId: Uuid
        @Endpoint.Resource interface Posts : User
        @Endpoint.Resource("p:{postId}") interface Post : User {
            val postId: Uuid
        }
    }
}
```

defines the following paths:
- `/u`
- `/u/new`
- `/u/{userId}`
- `/u/{userId}/posts`
- `/u/{userId}/posts/p:{postId}`

### Services

After declaring its app resources, the user declares the services that define the API endpoints that will use these resources.

A service is an interface annotated with `@Endpoint.Service`, and must extend the EndpointService marker interface.
Each method in a service interface defines an API endpoint:
- It must be `suspend`.
- It must be an extension function on a resource interface.
- Its name must be an HTTP verb (amongst `get`, `post`, `put`, `patch` or `delete`), or be annotated with `@Endpoint` and define the http method.
- If its HTTP method is `post` or `put`, its first parameter may be the content of the request body.
- Any other parameter must be annotated with `@Endpoint.Query`, and be nullable (it may have a default value, but that is not required). If the name of the query parameter is not defined in the annotation, the name of the parameter is used.
- Its return type must be an `Either`, with the left `V` type being the type a successful response (`Unit` if none), and the right `E` type being the type of a business failure (`NoFail` if none).

Note: Standard HTTP errors (404 not found, 403 forbidden) should not be represented as "business failures".
A business failure is when the endpoint call failed for a business reason, not a resource reason (for example, because the client tried to create a user without a name).

For example, the current code:

```kotlin
@Endpoint.Service
interface UsersService : EndpointService {
    suspend fun Users.get(@Endpoint.Query page: Int? = null): Either<List<UserSummary>, NoFail>
    @Endpoint(Endpoint.Method.Post) suspend fun Users.New.create(user: NewUser): Either<Uuid, UserCreationFailure>
    suspend fun User.get(): Either<User, NoFail>
    suspend fun User.delete(): Either<Unit, NoFail>
    suspend fun User.Posts.get(@Endpoint.Query("p") page: Int? = null): Either<List<UserPost>, NoFail>
    suspend fun User.Posts.post(content: String): Either<Uuid, NoFail>
    suspend fun User.Posts.Post.get(): Either<UserPost, NoFail>
    suspend fun User.Posts.Post.delete(): Either<Unit, UserPostDeletionFailure>
}
```

defines the following endpoints:
```
- GET    /u?page
- POST   /u/new
- GET    /u/user/{userId}
- DELETE /u/user/{userId}
- GET    /u/user/{userId}/posts?p
- POST   /u/user/{userId}/posts?p
- GET    /u/user/{userId}/posts/p:{postId}
- DELETE /u/user/{userId}/posts/p:{postId}
```

#### Stringers

All resource path parameters and all service endpoint query arguments must be converted to `String` by the client, and parsed by the server.
That is the role of the `Stringer` interface.
Built-in Stringers are provided for primitives (`Boolean`, `Int`, and `Long`).
For any other type, the user must provide a `Stringer` for the specific type.
To do so, the user must first create an object that implements the `Stringer` interface (it must be an `object`, neither a `class` nor an `interface` are valid).
Then, the user must associate the `Stringer` with the type it is used.
This can be done in multiple ways:
- By annotating the property or query parameter with `@Endpoint.Stringed`.
  ```kotlin
  @Endpoint.Resource interface User : Users {
      @EndPoint.Stringed(with = UuidStringer::class)
      val userId: Uuid
  }
  @Endpoint.Service interface UsersService : EndpointService {
    suspend fun Users.get(
        @EndPoint.Stringed(with = UuidStringer::class)
        @Endpoint.Query groupId: Uuid? = null
    ): Either<List<UserSummary>, NoFail>
  }
  ```
- By annotating the interface with `@EndPoint.UseStringer` (note that it is repeatable, so multiple Stingers may be registered to the same interface).
  ```kotlin
  @EndPoint.UseStringer(type = Uuid::class, with = UuidStringer::class)
  @Endpoint.Resource interface User : Users {
      val userId: Uuid
  }
  @EndPoint.UseStringer(type = Uuid::class, with = UuidStringer::class)
  @Endpoint.Service interface UsersService : EndpointService {
      suspend fun Users.get(@Endpoint.Query groupId: Uuid? = null): Either<List<UserSummary>, NoFail>
  }
  ```
- By annotating any parent interface with `@EndPoint.UseStringer`.
  ```kotlin
  @EndPoint.UseStringer(type = Uuid::class, with = UuidStringer::class)
  @Endpoint.Resource interface Users {
      @Endpoint.Resource interface User : Users {
          val userId: Uuid
      }
  }
  @Endpoint.Service
  @EndPoint.UseStringer(type = Uuid::class, with = UuidStringer::class)
  interface ServiceBase : EndpointService
  @Endpoint.Service interface UsersService : ServiceBase {
      suspend fun Users.get(@Endpoint.Query groupId: Uuid? = null): Either<List<UserSummary>, NoFail>
  }
  ```
- By creating a custom annotation, itself annotated with `@EndPoint.UseStringer`, and using that annotation on a resource interface (parent included).
  ```kotlin
  @Retention(AnnotationRetention.BINARY)
  @Target(AnnotationTarget.CLASS)
  @EndPoint.UseStringer(type = Uuid::class, with = UuidStringer::class)
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

### Client request

To request the client processor to generate a ktor client for a service, the user needs to annotate an `object` with `@ClientEndpointServiceFactory`.

```kotlin
@ClientEndpointServiceFactory(UsersService::class, "users")
object APIServices
```

## Processors

The processors are located in four different modules:
- `processor-shared` is the KSP processor that generates code shared between server & client.
- `processor-server` is the KSP processor that generates server code.
- `processor-client` is the KSP processor that generates client code.
- `processor-common` contains common code used by all three processors (models, analysis, verification).
  It is not a runnable processor.

### Share processor

For each `@Endpoint.Resource` interface, the share processor generates a public data class with the same name as the interface, but prefixed with `Nomi`, annotated with `@NomiInternalAPI`.
It also generates an associated factory function that allows the user to create the data object or class by emulating the nested resource route.

Example:
```kotlin
@InternalNomiAPI public data object NomiUsers : Users
@InternalNomiAPI public data object NomiUsersNew : Users.New
@InternalNomiAPI public data class NomiUsersUser(override val userId: Uuid) : User
@InternalNomiAPI public data class NomiUsersUserPosts(override val userId: Uuid) : User.Posts

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
public fun Users.User.Post(): Users.User.Post = NomiUsersUserPosts(userId)
```

### Server processor

For each `@ServerEndpointService` annotated class, the server processor generates, in the same package, a `fun Route.service(service: <ServiceImplementation>)` function.
That function registers all endpoints declared in all `@Endpoint.Service` interfaces this class implements.

For each endpoint, the function generates a nested ktor route and calls the associated service method with:
- The resource object implementation generated by the shared processor.
- An input parameter received from ktor (if it has an input)
- Set query parameters if any is not null

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
                            content = call.receive()
                        )
                }
            }
        }
        route("/user/{userId}") {
            get {
                call.respond(service) {
                    NomiUser(
                        userId = decode(UuidStringer, call.parameters["userId"]!!)
                    )
                    .get()
                }
            }
          // ...
        }
    }
}
```
Note that `decodeOrNull`, `decode` and `call.respond` are functions that are public functions provided by the runtime, under the `@InternalNomiAPI` optin annotation.

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

For each `@ClientEndpointService` annotated object, the client processor generates, in the same package:
- A private implementation of the service interface that also implements `NomiClientEndpointService`.
- A public extension function on the annotated object that creates a `ServiceCaller` for the service interface.

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
            setBody(content)
        }

    override suspend fun Users.User.get(): Either<User, NoFail> =
        client.call(
            pathSegments = listOf(
                "u",
                "users",
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
