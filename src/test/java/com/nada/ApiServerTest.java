package com.nada;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end tests against a real server on a random port. */
class ApiServerTest {

    private ApiServer server;
    private HttpClient client;
    private String base;

    @BeforeEach
    void start() throws Exception {
        server = new ApiServer(0, new PeopleStore());
        server.start();
        base = "http://localhost:" + server.port();
        client = HttpClient.newHttpClient();
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    // ------------------------------------------------------------- required

    @Test
    void pathRespectsThreeHopLimitOnBothSides() throws Exception {
        int a = createPerson("A");
        int b = createPerson("B");
        int c = createPerson("C");
        int d = createPerson("D");
        knows(a, b);
        knows(b, c);
        knows(c, d);

        // A - B - C - D : A to D is connected in 3 hops
        JsonObject ad = getJson("/path?from=" + a + "&to=" + d).getAsJsonObject();
        assertTrue(ad.get("connected").getAsBoolean());
        assertEquals(3, ad.get("hops").getAsInt());
        JsonArray path = ad.getAsJsonArray("path");
        assertEquals(4, path.size());
        assertEquals(a, path.get(0).getAsJsonObject().get("id").getAsInt());
        assertEquals(d, path.get(3).getAsJsonObject().get("id").getAsInt());

        // A - B - C - D - E : A to E is not connected (4 hops)
        int e = createPerson("E");
        knows(d, e);
        JsonObject ae = getJson("/path?from=" + a + "&to=" + e).getAsJsonObject();
        assertFalse(ae.get("connected").getAsBoolean());
        assertFalse(ae.has("path"));
    }

    // ------------------------------------------------------------ endpoints

    @Test
    void createPersonReturns201WithId() throws Exception {
        HttpResponse<String> res = post("/people", "{\"name\":\"Asha\"}");
        assertEquals(201, res.statusCode());
        JsonObject body = JsonParser.parseString(res.body()).getAsJsonObject();
        assertEquals(1, body.get("id").getAsInt());
        assertEquals("Asha", body.get("name").getAsString());
    }

    @Test
    void hindiNamesRoundTripExactly() throws Exception {
        int id = createPerson("आशा शर्मा");
        int other = createPerson("Rohan");
        knows(other, id);
        JsonArray contacts = getJson("/people/" + other + "/contacts").getAsJsonArray();
        assertEquals("आशा शर्मा", contacts.get(0).getAsJsonObject().get("name").getAsString());
    }

    @Test
    void knowsIsMutualIdempotentAndReturns204() throws Exception {
        int a = createPerson("Asha");
        int b = createPerson("Rohan");
        assertEquals(204, post("/knows", knowsBody(a, b)).statusCode());
        assertEquals(204, post("/knows", knowsBody(a, b)).statusCode());
        assertEquals(204, post("/knows", knowsBody(b, a)).statusCode());

        JsonArray aContacts = getJson("/people/" + a + "/contacts").getAsJsonArray();
        JsonArray bContacts = getJson("/people/" + b + "/contacts").getAsJsonArray();
        assertEquals(1, aContacts.size());
        assertEquals(1, bContacts.size());
        assertEquals(b, aContacts.get(0).getAsJsonObject().get("id").getAsInt());
    }

    @Test
    void contactsEmptyListForLonePerson() throws Exception {
        int a = createPerson("Asha");
        HttpResponse<String> res = get("/people/" + a + "/contacts");
        assertEquals(200, res.statusCode());
        assertEquals("[]", res.body());
    }

    @Test
    void samePersonPathIsZeroHops() throws Exception {
        int a = createPerson("Asha");
        JsonObject body = getJson("/path?from=" + a + "&to=" + a).getAsJsonObject();
        assertTrue(body.get("connected").getAsBoolean());
        assertEquals(0, body.get("hops").getAsInt());
        assertEquals(1, body.getAsJsonArray("path").size());
    }

    // ----------------------------------------------------------- validation

    @Test
    void unknownPersonReturns404WithError() throws Exception {
        int a = createPerson("Asha");
        assertError(get("/people/9/contacts"), 404, "no person 9");
        assertError(get("/path?from=" + a + "&to=9"), 404, "no person 9");
        assertError(get("/path?from=9&to=" + a), 404, "no person 9");
        assertError(post("/knows", knowsBody(a, 9)), 404, "no person 9");
    }

    @Test
    void badPersonBodiesReturn400() throws Exception {
        assertEquals(400, post("/people", "{not json").statusCode());
        assertEquals(400, post("/people", "").statusCode());
        assertEquals(400, post("/people", "{}").statusCode());
        assertEquals(400, post("/people", "{\"name\":\"\"}").statusCode());
        assertEquals(400, post("/people", "{\"name\":\"   \"}").statusCode());
        assertEquals(400, post("/people", "{\"name\":null}").statusCode());
        assertEquals(400, post("/people", "{\"name\":42}").statusCode());
        assertEquals(400, post("/people", "[]").statusCode());
    }

    @Test
    void badKnowsBodiesReturn400() throws Exception {
        int a = createPerson("Asha");
        assertEquals(400, post("/knows", "{oops").statusCode());
        assertEquals(400, post("/knows", "{\"a\":" + a + "}").statusCode());
        assertEquals(400, post("/knows", "{\"b\":" + a + "}").statusCode());
        assertEquals(400, post("/knows", "{\"a\":\"x\",\"b\":1}").statusCode());
        assertEquals(400, post("/knows", "{\"a\":1.5,\"b\":1}").statusCode());
        assertEquals(400, post("/knows", knowsBody(a, a)).statusCode());
    }

    @Test
    void badPathParamsReturn400() throws Exception {
        int a = createPerson("Asha");
        assertEquals(400, get("/path").statusCode());
        assertEquals(400, get("/path?from=" + a).statusCode());
        assertEquals(400, get("/path?to=" + a).statusCode());
        assertEquals(400, get("/path?from=abc&to=" + a).statusCode());
        assertEquals(400, get("/path?from=" + a + "&to=").statusCode());
        assertEquals(400, get("/people/abc/contacts").statusCode());
    }

    // -------------------------------------------------------------- helpers

    private int createPerson(String name) throws Exception {
        JsonObject req = new JsonObject();
        req.addProperty("name", name);
        HttpResponse<String> res = post("/people", req.toString());
        assertEquals(201, res.statusCode());
        return JsonParser.parseString(res.body()).getAsJsonObject().get("id").getAsInt();
    }

    private void knows(int a, int b) throws Exception {
        assertEquals(204, post("/knows", knowsBody(a, b)).statusCode());
    }

    private static String knowsBody(int a, int b) {
        return "{\"a\":" + a + ",\"b\":" + b + "}";
    }

    private JsonElement getJson(String path) throws Exception {
        HttpResponse<String> res = get(path);
        assertEquals(200, res.statusCode(), res.body());
        return JsonParser.parseString(res.body());
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + path)).GET().build();
        return client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static void assertError(HttpResponse<String> res, int status, String message) {
        assertEquals(status, res.statusCode(), res.body());
        JsonObject body = JsonParser.parseString(res.body()).getAsJsonObject();
        assertEquals(message, body.get("error").getAsString());
    }
}
