# Nada – People & Connections API

A small in-memory HTTP API (Java 17, JDK built-in `HttpServer` + Gson) that stores people and their mutual contacts, and finds the shortest connection between two people within three hops.

## Requirements

- Java 17 or newer
- Maven 3.8+

## Run the server

```bash
mvn -q package -DskipTests
java -jar target/nada-connections-1.0.0.jar
```

The server listens on **port 8080** (`http://localhost:8080`). Set the `PORT` environment variable to use a different port:

```bash
PORT=9090 java -jar target/nada-connections-1.0.0.jar
```

Data lives in memory and resets on restart.

## Run the tests

```bash
mvn test
```

- `ApiServerTest` – end-to-end HTTP tests on a random port, including the required three-hop test (`A-B-C-D` connected in 3 hops; `A-B-C-D-E` gives A→E not connected), plus validation cases.
- `PeopleStoreTest` – unit tests for the graph and shortest-path logic.

## API

| Method | Path | Success | Notes |
|---|---|---|---|
| POST | `/people` | 201 `{"id":1,"name":"Asha"}` | body `{"name": "..."}` |
| POST | `/knows` | 204 | body `{"a":1,"b":2}`; repeating is fine |
| GET | `/people/{id}/contacts` | 200 `[{"id":2,"name":"Rohan"}]` | empty list if none |
| GET | `/path?from=1&to=5` | 200 | `{"connected":true,"hops":2,"path":[...]}` or `{"connected":false}` |

Errors return `{"error": "..."}`:
- **400** – invalid JSON, missing/non-integer field, blank name, `a == b`, missing or malformed `from`/`to`/`id`.
- **404** – unknown person (`{"error":"no person 9"}`) or unknown route.
- **405** – wrong HTTP method.

Quick try:

```bash
curl -s -XPOST localhost:8080/people -d '{"name":"Asha"}'
curl -s -XPOST localhost:8080/people -d '{"name":"रोहन"}'
curl -s -XPOST localhost:8080/knows  -d '{"a":1,"b":2}' -o /dev/null -w '%{http_code}\n'
curl -s localhost:8080/people/1/contacts
curl -s 'localhost:8080/path?from=1&to=2'
```

## Design notes

- `PeopleStore` holds people in a `ConcurrentHashMap` and contacts as an adjacency set per person, so duplicate connections are naturally ignored.
- Shortest path uses breadth-first search, expanded level by level and stopped after 3 levels, so it never explores further than needed.
- `ApiServer` handles routing, validation and JSON; the store has no HTTP knowledge.
- No framework: the JDK's `com.sun.net.httpserver` keeps dependencies to Gson only.

## What I would do next with more time

- Bidirectional BFS for large graphs (meets in the middle, explores far fewer nodes).
- Persistence (e.g. PostgreSQL or Neo4j) behind a repository interface.
- Pagination for contacts, and endpoints to delete people/connections.
- Structured logging, request IDs, and a health-check endpoint.
- OpenAPI spec and a Dockerfile.
- Stricter concurrency guarantees around connect/delete once deletes exist.

## AI tools used

<!-- Edit this honestly before submitting. -->
- Lovable AI (Claude) helped scaffold the project, the code and the tests; I reviewed, ran and adjusted them.
