package com.nada;

/**
 * Entry point. Starts the HTTP server on port 8080 (override with the PORT
 * environment variable).
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        int port = 8080;
        String envPort = System.getenv("PORT");
        if (envPort != null && !envPort.isBlank()) {
            port = Integer.parseInt(envPort.trim());
        }

        ApiServer server = new ApiServer(port, new PeopleStore());
        server.start();
        System.out.println("Nada connections API listening on http://localhost:" + server.port());
    }
}
