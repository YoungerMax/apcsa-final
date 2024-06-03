import com.sun.net.httpserver.HttpsServer;

import javax.net.ssl.*;
import javax.xml.stream.util.StreamReaderDelegate;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URL;
import java.security.*;
import java.security.cert.CertificateException;
import java.util.Date;
import java.util.Optional;

public class Main {
    public static SSLContext createSSLContext() throws NoSuchAlgorithmException, KeyStoreException, IOException, CertificateException, UnrecoverableKeyException, KeyManagementException, NoSuchProviderException {
        try (FileInputStream fis = new FileInputStream("cert.pfx")) {
            KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
            trustStore.load(fis, "password".toCharArray());

            TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagerFactory.init(trustStore);

            KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keyManagerFactory.init(trustStore, "password".toCharArray());
            SSLContext tls = SSLContext.getInstance("TLS");

            tls.init(
                    keyManagerFactory.getKeyManagers(),
                    trustManagerFactory.getTrustManagers(),
                    null
            );

            return tls;
        }
    }

    public static void main(String[] args) throws IOException, NoSuchAlgorithmException, UnrecoverableKeyException, CertificateException, KeyStoreException, KeyManagementException, NoSuchProviderException {
        ExtendedHttpServer server = new ExtendedHttpServer(new InetSocketAddress("0.0.0.0", 8080), createSSLContext());

        server
                .route("GET", "/", (request, client, next) -> {
                    return HttpServer.responseBuilder()
                            .code(200)
                            .status("OK")
                            .httpVersion("HTTP/1.0")
                            .header("Date", new Date().toString())
                            .header("Content-Type", "text/html")
                            .content("<h1>Hello World!</h1>")
                            .build();
                })
                .route("GET", "/api", (request, client, next) -> {
                    return HttpServer.responseBuilder()
                            .content("{\"version\": 1}")
                            .build();
                })
                .route("GET", "/api/posts", (request, client, next) -> {
                    return HttpServer.responseBuilder()
                            .content("{}")
                            .build();
                })
                .route("GET", "/api/posts/*", (request, client, next) -> {
                    String substring = request.resource.substring(11);
                    int idx = substring.indexOf("/");
                    int i;

                    if (idx > -1) {
                        substring = substring.substring(0, idx);
                    }

                    try {
                        i = Integer.parseInt(substring);
                        return HttpServer.responseBuilder().content("Param: " + i).ok().httpVersion("HTTP/1.0").build();
                    } catch (Exception e) {
                        throw new HttpException(HttpServer.responseBuilder().status("Bad Request").code(400).httpVersion("HTTP/1.0").build(), "Bad Request");
                    }
                })
                .route("GET", "/api*", (request, client, next) -> {
                    HttpServer.Response r = next.get();
                    if (r == null) return null;

                    r.code = 200;
                    r.status = "OK";
                    r.httpVersion = "HTTP/1.0";
                    r.headers.put("Content-Type", "application/json");

                    return r;
                })
                .middleware((request, client, next) -> {
                    long start = System.nanoTime();
                    HttpServer.Response r = next.get();
                    if (r == null) return null;
                    long end = System.nanoTime();
                    r.headers.put("X-Request-Time", String.valueOf(end - start));

                    return r;
                });

        server.start();
    }
}
