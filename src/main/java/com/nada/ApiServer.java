package com.nada;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTTP layer built on the JDK's built-in {@link HttpServer}. Translates
 * requests into {@link PeopleStore} calls and maps validation failures to
 * 400/404 responses with an {@code {"error": "..."}} body.
 */
public final class ApiServer {

    static final int MAX_HOPS = 3;

    private static final Pattern CONTACTS_PATH = Pattern.compile("^/people/([^/]+)/contacts/?$");

    private final HttpServer server;
    private final PeopleStore store;
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();

    public ApiServer(int port, PeopleStore store) throws IOException {
        this.store = store;
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        this.server.createContext("/", this::handle);
        this.server.setExecutor(Executors.newFixedThreadPool(8));
    }

    public void start() {
        server.start();
    }

    public void stop() {
        server.stop(0);
    }

    /** Actual bound port (useful when started with port 0 in tests). */
    public int port() {
        return server.getAddress().getPort();
    }

    // ---------------------------------------------------------------- routing

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();

            if (path.equals("/people") || path.equals("/people/")) {
                requireMethod(method, "POST");
                createPerson(exchange);
            } else if (path.equals("/knows") || path.equals("/knows/")) {
                requireMethod(method, "POST");
                createConnection(exchange);
            } else if (path.equals("/path") || path.equals("/path/")) {
                requireMethod(method, "GET");
                findPath(exchange);
            } else {
                Matcher m = CONTACTS_PATH.matcher(path);
                if (m.matches()) {
                    requireMethod(method, "GET");
                    listContacts(exchange, m.group(1));
                } else {
                    throw new ApiException(404, "not found");
                }
            }
        } catch (ApiException e) {
            sendError(exchange, e.status, e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
            sendError(exchange, 500, "internal error");
        } finally {
            exchange.close();
        }
    }

    private static void requireMethod(String actual, String expected) {
        if (!expected.equalsIgnoreCase(actual)) {
            throw new ApiException(405, "method not allowed");
        }
    }

    // --------------------------------------------------------------- handlers

    private void createPerson(HttpExchange exchange) throws IOException {
        JsonObject body = readJsonObject(exchange);
        JsonElement nameEl = body.get("name");
        if (nameEl == null || nameEl.isJsonNull()) {
            throw new ApiException(400, "name is required");
        }
        if (!isString(nameEl)) {
            throw new ApiException(400, "name must be a string");
        }
        String name = nameEl.getAsString();
        if (name.isBlank()) {
            throw new ApiException(400, "name must not be blank");
        }
        Person person = store.addPerson(name);
        sendJson(exchange, 201, person);
    }

    private void createConnection(HttpExchange exchange) throws IOException {
        JsonObject body = readJsonObject(exchange);
        int a = requireIntField(body, "a");
        int b = requireIntField(body, "b");
        if (a == b) {
            throw new ApiException(400, "a person cannot know themselves");
        }
        requirePerson(a);
        requirePerson(b);
        store.connect(a, b);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(204, -1);
    }

    private void listContacts(HttpExchange exchange, String rawId) throws IOException {
        int id = parseId(URLDecoder.decode(rawId, StandardCharsets.UTF_8), "id");
        requirePerson(id);
        sendJson(exchange, 200, store.contactsOf(id));
    }

    private void findPath(HttpExchange exchange) throws IOException {
        Map<String, String> query = parseQuery(exchange.getRequestURI());
        int from = requireIntParam(query, "from");
        int to = requireIntParam(query, "to");
        requirePerson(from);
        requirePerson(to);

        Optional<List<Person>> path = store.shortestPath(from, to, MAX_HOPS);
        Map<String, Object> response = new LinkedHashMap<>();
        if (path.isPresent()) {
            response.put("connected", true);
            response.put("hops", path.get().size() - 1);
            response.put("path", path.get());
        } else {
            response.put("connected", false);
        }
        sendJson(exchange, 200, response);
    }

    // ------------------------------------------------------------- validation

    private void requirePerson(int id) {
        if (!store.exists(id)) {
            throw new ApiException(404, "no person " + id);
        }
    }

    private JsonObject readJsonObject(HttpExchange exchange) throws IOException {
        String raw;
        try (InputStream in = exchange.getRequestBody()) {
            raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        if (raw.isBlank()) {
            throw new ApiException(400, "request body is required");
        }
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(raw);
        } catch (JsonParseException e) {
            throw new ApiException(400, "invalid JSON");
        }
        if (!parsed.isJsonObject()) {
            throw new ApiException(400, "request body must be a JSON object");
        }
        return parsed.getAsJsonObject();
    }

    private static boolean isString(JsonElement el) {
        return el.isJsonPrimitive() && el.getAsJsonPrimitive().isString();
    }

    private static int requireIntField(JsonObject body, String field) {
        JsonElement el = body.get(field);
        if (el == null || el.isJsonNull()) {
            throw new ApiException(400, field + " is required");
        }
        if (!el.isJsonPrimitive() || !el.getAsJsonPrimitive().isNumber()) {
            throw new ApiException(400, field + " must be an integer");
        }
        JsonPrimitive p = el.getAsJsonPrimitive();
        try {
            return new java.math.BigDecimal(p.getAsString()).intValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            throw new ApiException(400, field + " must be an integer");
        }
    }

    private static int requireIntParam(Map<String, String> query, String name) {
        String value = query.get(name);
        if (value == null || value.isBlank()) {
            throw new ApiException(400, name + " is required");
        }
        return parseId(value, name);
    }

    private static int parseId(String value, String name) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new ApiException(400, name + " must be an integer");
        }
    }

    private static Map<String, String> parseQuery(URI uri) {
        Map<String, String> result = new HashMap<>();
        String raw = uri.getRawQuery();
        if (raw == null || raw.isEmpty()) {
            return result;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            result.putIfAbsent(
                    URLDecoder.decode(key, StandardCharsets.UTF_8),
                    URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return result;
    }

    // -------------------------------------------------------------- responses

    private void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = gson.toJson(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private void sendError(HttpExchange exchange, int status, String message) throws IOException {
        sendJson(exchange, status, Map.of("error", message));
    }

    /** Signals a client-visible error with an HTTP status. */
    static final class ApiException extends RuntimeException {
        final int status;

        ApiException(int status, String message) {
            super(message);
            this.status = status;
        }
    }
}
