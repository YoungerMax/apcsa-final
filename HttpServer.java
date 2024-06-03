import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.*;

public abstract class HttpServer {
    private final SocketAddress address;
    private final SSLContext sslContext;
    private ServerSocket socket;
    private boolean running;

    public HttpServer(SocketAddress address, SSLContext sslContext) {
        this.address = address;
        this.sslContext = sslContext;
    }

    public HttpServer(SocketAddress address) {
        this(address, null);
    }

    public void start() throws IOException {
        this.start(Executors.newCachedThreadPool());
    }

    public void start(ExecutorService service) throws IOException {
        if (this.sslContext == null) {
            this.socket = new ServerSocket();
        } else {
            this.socket = this.sslContext.getServerSocketFactory().createServerSocket();
        }

        this.socket.bind(this.address);
        this.running = true;
        Timer t = new Timer();

        while (this.running) {
            try {
                Socket client = this.socket.accept();

                Future<?> task = service.submit(() -> {
                    try {
                        InputStream inputStream = client.getInputStream();
                        Response response;

                        try {
                            Request request = new Request(inputStream);
                            response = this.handle(request, client);
                        } catch (Exception e) {
                            response = this.handleError(client, e);
                        }

                        response.write(client.getOutputStream());
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                });

                t.schedule(new TimerTask() {
                    @Override
                    public void run() {
                        try {
                            task.get(10000L, TimeUnit.MILLISECONDS);
                        } catch (InterruptedException | ExecutionException | TimeoutException e) {
                            e.printStackTrace();
                        }
                    }
                }, 10 * 1000L);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public abstract Response handle(Request request, Socket client) throws Exception;

    public abstract Response handleError(Socket client, Exception exception);

    public static Response status(int code, String status, String httpVersion) {
        return content(code, status, httpVersion, status);
    }

    public static Response content(int code, String status, String httpVersion, String content) {
        return responseBuilder().code(code).status(status).httpVersion(httpVersion).content(content).build();
    }

    public static class Request {
        public String method;
        public String resource;
        public String httpVersion;
        public Map<String, String> headers;
        public InputStream content;

        public Request(InputStream stream) throws IOException {
            this.headers = new HashMap<>();
            StringBuilder builder = new StringBuilder();

            byte[] buffer = new byte[10240];
            int readBytes;
            Parsing parsing = Parsing.METHOD;
            String headerName = null;

            parseLoop:
            do {
                readBytes = stream.read(buffer);

                for (int i = 0; readBytes > i; i++) {
                    byte b = buffer[i];

                    switch (parsing) {
                        case METHOD:
                            if (b == ' ') {
                                parsing = Parsing.RESOURCE;
                                this.method = builder.toString();
                                builder.delete(0, builder.length());
                                break;
                            }

                            builder.append((char) b);
                            break;

                        case RESOURCE:
                            if (b == ' ') {
                                parsing = Parsing.HTTP_VERSION;
                                this.resource = builder.toString();
                                builder.delete(0, builder.length());
                                break;
                            }

                            builder.append((char) b);
                            break;

                        case HTTP_VERSION:
                            if (b == '\r') {
                                parsing = Parsing.STATUS_EOL;
                                this.httpVersion = builder.toString();
                                builder.delete(0, builder.length());
                                break;
                            }

                            builder.append((char) b);
                            break;

                        case HEADER_KEY:
                            if (b == '\r') {
                                parsing = Parsing.CONTENT_SEPARATOR;
                                break;
                            }

                            if (b == ':') {
                                parsing = Parsing.HEADER_SPACE;
                                headerName = builder.toString();
                                builder.delete(0, builder.length());
                                break;
                            }

                            builder.append((char) b);
                            break;

                        case HEADER_SPACE:
                            if (b != ' ') {
                                throw new IOException("expecting HEADER_SPACE, got: " + b);
                            }

                            parsing = Parsing.HEADER_VALUE;
                            break;

                        case HEADER_VALUE:
                            if (b == '\r') {
                                if (headerName == null) {
                                    throw new IOException("Something went wrong: headerName == null");
                                }

                                parsing = Parsing.HEADER_EOL;
                                this.headers.put(headerName, builder.toString());
                                builder.delete(0, builder.length());
                                headerName = null;
                                break;
                            }

                            builder.append((char) b);
                            break;

                        case HEADER_EOL:
                        case STATUS_EOL:
                            if (b != '\n') {
                                throw new IOException("unexpected while parsing " + parsing.name() + ": " + b);
                            }

                            parsing = Parsing.HEADER_KEY;
                            break;

                        case CONTENT_SEPARATOR:
                            if (b != '\n') {
                                throw new IOException("expected CONTENT_SEPARATOR, got: " + b);
                            }

                            this.content = new ContentInputStream(stream, buffer, i + 1);
                            break parseLoop;
                    }
                }
            } while (readBytes > 0);
        }

        enum Parsing {
            METHOD,
            RESOURCE,
            HTTP_VERSION,
            STATUS_EOL,
            HEADER_KEY,
            HEADER_SPACE,
            HEADER_VALUE,
            HEADER_EOL,
            CONTENT_SEPARATOR;
        }

        private static class ContentInputStream extends InputStream {
            private final InputStream stream;
            private final byte[] beginning;
            private int pos;

            public ContentInputStream(InputStream stream, byte[] beginning, int startIdx) {
                this.stream = stream;
                this.beginning = beginning;
                this.pos = startIdx;
            }


            @Override
            public int read() throws IOException {
                if (this.beginning.length > this.pos) {
                    return this.beginning[this.pos++] & 0xFF;
                } else {
                    return this.stream.read();
                }
            }
        }
    }

    public static class Response {
        public int code;
        public String status;
        public String httpVersion;
        public Map<String, String> headers;
        public InputStream content;

        public Response() {
            this.headers = new HashMap<>();
        }

        public void write(OutputStream os) throws IOException {
            os.write(String.format("%s %d %s\r\n", this.httpVersion, this.code, this.status).getBytes(StandardCharsets.UTF_8));

            for (Map.Entry<String, String> entry : this.headers.entrySet()) {
                String key = entry.getKey();
                String val = entry.getValue();

                os.write((key + ": " + val + "\r\n").getBytes(StandardCharsets.UTF_8));
            }

            os.write("\r\n".getBytes(StandardCharsets.UTF_8));

            byte[] buffer = new byte[10240];

            if (this.content != null) {
                while (true) {
                    int bytesRead = this.content.read(buffer);

                    if (bytesRead > 0) {
                        os.write(buffer, 0, bytesRead);
                    } else {
                        break;
                    }
                }

                this.content.close();
            }

            os.close();
        }
    }

    public static class ResponseBuilder {
        private final Response response;

        ResponseBuilder() {
            this.response = new Response();
            this.response.headers = new HashMap<>();
        }

        public ResponseBuilder code(int code) {
            this.response.code = code;
            return this;
        }

        public ResponseBuilder status(String status) {
            this.response.status = status;
            return this;
        }

        public ResponseBuilder httpVersion(String httpVersion) {
            this.response.httpVersion = httpVersion;
            return this;
        }

        public ResponseBuilder header(String key, String value) {
            this.response.headers.put(key, value);
            return this;
        }

        public ResponseBuilder headers(Map<String, String> headers) {
            this.response.headers.putAll(headers);
            return this;
        }

        public ResponseBuilder content(InputStream content) {
            this.response.content = content;
            return this;
        }

        public ResponseBuilder content(InputStream content, long length) {
            return this.content(content).header("Content-Length", String.valueOf(length));
        }

        public ResponseBuilder content(String content) {
            return this.content(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), content.length());
        }

        public ResponseBuilder ok() {
            return this.code(200).status("OK");
        }

        public Response build() {
            return this.response;
        }
    }

    public static ResponseBuilder responseBuilder() {
        return new ResponseBuilder();
    }
}
