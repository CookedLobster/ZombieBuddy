package me.zed_0xff.zombie_buddy.patches.experimental;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import me.zed_0xff.zombie_buddy.*;

public class PreMain {
    public static void premain(String[] args) {
        JavaStateDumper.init();
        if (Agent.isDevToolsEnabled()) {
            WatchesAPI.init();
        } else {
            Logger.info("Watches API disabled: requires experimental=true and devtools=true.");
        }

        // Configure HTTP server host, port and timeout from Agent.arguments (if provided)
        String hostValue    = Agent.arguments.get("lua_server_host");
        String portValue    = Agent.arguments.get("lua_server_port");
        String timeoutValue = Agent.arguments.get("lua_task_timeout");
        String tokenValue   = Agent.arguments.get("lua_server_token");
        boolean allowRemote = Boolean.parseBoolean(
            Agent.arguments.getOrDefault("lua_server_allow_remote", "false").trim()
        );

        if (!Utils.isBlank(portValue)) {
            try {
                int serverPort;
                boolean isRandomPort = false;
                if ("random".equalsIgnoreCase(portValue)) {
                    serverPort = 0; // 0 means random port
                    isRandomPort = true;
                    Logger.info("Using random port for HTTP server");
                } else {
                    serverPort = Integer.parseInt(portValue);
                    if (serverPort == 0) {
                        isRandomPort = true;
                        Logger.info("Using random port for HTTP server");
                    }
                }

                HttpServer.g_verbosity = Logger.getLevel();
                String requestedHost = (!Utils.isBlank(hostValue)) ? hostValue.trim() : "127.0.0.1";
                String bindHost = resolveBindHost(requestedHost, allowRemote);

                String token = tokenValue;
                boolean generatedToken = false;
                if (!HttpAuth.isValidToken(token)) {
                    if (!Utils.isBlank(tokenValue)) {
                        Logger.warn("lua_server_token is invalid (minimum " + HttpAuth.MIN_TOKEN_LENGTH
                            + " characters, no whitespace); generating a random token.");
                    }
                    token = HttpAuth.generateToken();
                    generatedToken = true;
                }
                HttpServer.setAuthToken(token);
                // A user-supplied token is usable immediately; a generated token is only
                // usable after it has been written to the token file.
                if (!generatedToken) {
                    HttpServer.setAuthReady(true);
                }

                final String authToken = token;
                final boolean authTokenGenerated = generatedToken;
                Callbacks.onGameInitComplete.register(() -> {
                    boolean wroteToken = writeTokenFile(authToken);
                    if (authTokenGenerated) {
                        HttpServer.setAuthReady(wroteToken);
                        if (!wroteToken) {
                            Logger.error("Could not persist the HTTP API token; /lua and /log are disabled (503).");
                        }
                    } else if (!wroteToken) {
                        Logger.warn("Could not write the HTTP API token file; the supplied lua_server_token remains active.");
                    }

                    HttpServer server = HttpServer.getInstance();
                    if (server != null && server.wasRandomPort()) {
                        writePortFile(server.getPort());
                    }
                });

                HttpServer httpServer = new HttpServer(bindHost, serverPort, isRandomPort);
                httpServer.start();
            } catch (NumberFormatException e) {
                Logger.error("invalid server_port value: " + portValue);
            } catch (Exception e) {
                Logger.error("failed to start HTTP server: " + e.getMessage());
            }
        }

        // Configure Lua task timeout from Agent.arguments (if provided)
        if (!Utils.isBlank(timeoutValue)) {
            try {
                HttpServer.luaTaskTimeoutMs = Long.parseLong(timeoutValue);
                Logger.info("Lua task timeout set to " + HttpServer.luaTaskTimeoutMs + "ms");
            } catch (NumberFormatException e) {
                Logger.error("invalid lua_task_timeout value: " + timeoutValue);
            }
        }
    }

    /**
     * Keeps the HTTP API loopback-only unless remote binding was explicitly opted into.
     */
    private static String resolveBindHost(String requestedHost, boolean allowRemote) {
        if (HttpAuth.isLoopbackHost(requestedHost)) {
            return requestedHost;
        }
        if (!allowRemote) {
            Logger.warn("Refusing non-loopback lua_server_host '" + requestedHost
                + "' without lua_server_allow_remote=true; binding to 127.0.0.1 instead.");
            return "127.0.0.1";
        }
        Logger.warn("HTTP API bound to non-loopback host '" + requestedHost
            + "'. The bearer token is sent over cleartext HTTP; use a VPN or SSH tunnel.");
        return requestedHost;
    }

    private static Path tokenFile() {
        String cacheDir = Utils.getCacheDir();
        if (Utils.isBlank(cacheDir)) {
            return null;
        }
        return Path.of(cacheDir, HttpAuth.TOKEN_FILE_NAME);
    }

    private static boolean writeTokenFile(String token) {
        Path tokenFile = tokenFile();
        if (tokenFile == null) {
            Logger.error("Cannot locate the game cache directory for " + HttpAuth.TOKEN_FILE_NAME);
            return false;
        }
        try {
            Files.createDirectories(tokenFile.getParent());
            Utils.writeFileAtomic(tokenFile, token, StandardCharsets.UTF_8);
            try {
                Files.setPosixFilePermissions(tokenFile, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // Windows and non-POSIX filesystems.
            }
            Logger.info("Wrote HTTP API token to " + tokenFile.toAbsolutePath());
            return true;
        } catch (IOException e) {
            Logger.error("Failed to write HTTP API token file: " + e.getMessage());
            Logger.printStackTrace(e);
            return false;
        }
    }

    private static void writePortFile(int port) {
        try {
            Path portFile = Path.of(Utils.getCacheDir(), "zbLuaAPI.txt");
            Files.writeString(portFile, String.valueOf(port));
            Logger.info("Wrote random API port " + port + " to " + portFile.toAbsolutePath());
        } catch (IOException e) {
            Logger.error("Failed to write port file: " + e.getMessage());
            Logger.printStackTrace(e);
        }
    }
}
