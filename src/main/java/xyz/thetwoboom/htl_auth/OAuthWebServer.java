package xyz.thetwoboom.htl_auth;

import fi.iki.elonen.NanoHTTPD;

import java.util.Map;
import java.util.UUID;

public class OAuthWebServer extends NanoHTTPD {

    private HTL_auth plugin;

    public OAuthWebServer(int port, HTL_auth plugin) {
        super(port);
        this.plugin = plugin;
    }

    @Override
    public Response serve(IHTTPSession session) {
        if ("/auth".equals(session.getUri())) {
            // Hole die Parameter aus der Anfrage (Code und State)
            Map<String, String> params = session.getParms();
            String code = params.get("code");
            String state = params.get("state");

            if (code == null || state == null) {
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Fehlende Parameter.");
            }

            // Versuche, die UUID aus dem State zu parsen (State enthält die UUID des Spielers)
            try {
                UUID playerUUID = UUID.fromString(state);

                // Übergib den Code und die UUID an die Plugin-Methode
                plugin.handleOAuthCallback(code, playerUUID);

                return newFixedLengthResponse(Response.Status.OK, "text/plain", "Verifizierung erfolgreich!");

            } catch (IllegalArgumentException e) {
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Ungültige UUID.");
            }
        }

        return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Nicht gefunden.");
    }
}
