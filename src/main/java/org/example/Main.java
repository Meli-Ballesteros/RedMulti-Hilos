package org.example;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Main {

    // Pool de hilos para atender múltiples clientes
    private final ExecutorService pool =
            Executors.newFixedThreadPool(Math.max(4, Runtime.getRuntime().availableProcessors() * 4));

    public static void main(String[] args) throws IOException {
        new Main().init();
    }

    public void init() throws IOException {
        try (ServerSocket server = new ServerSocket(8050)) {
            System.out.println("Servidor HTTP en http://localhost:8050");
            while (true) {
                Socket socket = server.accept();
                pool.submit(() -> {
                    try {
                        handleRequest(socket);
                    } catch (IOException e) {
                        e.printStackTrace();
                        try { socket.close(); } catch (IOException ignored) {}
                    }
                });
            }
        }
    }

    // === Recibir -> Procesar -> Entregar ===
    private void handleRequest(Socket socket) throws IOException {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));

        // 1) Primera línea: "GET /ruta HTTP/1.1"
        String requestLine = reader.readLine();
        if (requestLine == null || requestLine.isEmpty()) {
            socket.close();
            return;
        }
        System.out.println(requestLine);

        String[] parts = requestLine.split(" ");
        String method = parts.length > 0 ? parts[0] : "";
        String path   = parts.length > 1 ? parts[1] : "/";

        // 2) Consumir cabeceras restantes
        String line;
        while ((line = reader.readLine()) != null && !line.isEmpty()) { /* ignore */ }

        // 3) Solo GET
        if (!"GET".equals(method)) {
            sendText(socket, 405, "Method Not Allowed", "<h1>405 Method Not Allowed</h1>");
            return;
        }

        // 4) Resolver recurso solicitado (con index.html por defecto)
        String resource = normalizePath(path);
        sendFile(socket, resource);
    }

    // Normaliza la ruta: quita '/', decodifica %20, resuelve index.html
    private String normalizePath(String path) throws UnsupportedEncodingException {
        String p = URLDecoder.decode(path, StandardCharsets.UTF_8);
        p = p.startsWith("/") ? p.substring(1) : p;
        if (p.isBlank()) return "index.html";      // GET / -> index.html
        if (p.endsWith("/")) return p + "index.html"; // GET /docs/ -> docs/index.html
        return p;
    }

    private void sendFile(Socket socket, String resource) throws IOException {
        // Base: carpeta "resources" en la raíz del proyecto
        Path base = Paths.get("resources").toAbsolutePath().normalize();
        Path filePath = base.resolve(resource).normalize();

        // Seguridad contra path traversal
        if (!filePath.startsWith(base)) {
            sendText(socket, 403, "Forbidden", "<h1>403 Forbidden</h1>");
            socket.close();
            return;
        }

        if (Files.exists(filePath) && !Files.isDirectory(filePath)) {
            byte[] body = Files.readAllBytes(filePath);

            String contentType = Files.probeContentType(filePath);
            if (contentType == null) contentType = guessContentType(resource);

            OutputStream out = socket.getOutputStream();
            String headers = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: " + contentType + "\r\n" +
                    "Content-Length: " + body.length + "\r\n" +
                    "Connection: close\r\n\r\n";

            out.write(headers.getBytes(StandardCharsets.UTF_8));
            out.write(body);
            out.flush();
        } else {
            sendText(socket, 404, "Not Found", "<h1>404 Not Found</h1>");
        }
        socket.close();
    }

    private void sendText(Socket socket, int code, String msg, String html) throws IOException {
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        OutputStream out = socket.getOutputStream();
        String headers = "HTTP/1.1 " + code + " " + msg + "\r\n" +
                "Content-Type: text/html; charset=UTF-8\r\n" +
                "Content-Length: " + body.length + "\r\n" +
                "Connection: close\r\n\r\n";
        out.write(headers.getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }

    private String guessContentType(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".html") || n.endsWith(".htm")) return "text/html; charset=UTF-8";
        if (n.endsWith(".css"))  return "text/css; charset=UTF-8";
        if (n.endsWith(".js"))   return "application/javascript; charset=UTF-8";
        if (n.endsWith(".png"))  return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".gif"))  return "image/gif";
        return "application/octet-stream ";
    }
}
