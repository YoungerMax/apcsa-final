import com.sun.net.httpserver.HttpsServer;

import javax.net.ssl.*;
import javax.xml.stream.util.StreamReaderDelegate;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.file.Files;
import java.security.*;
import java.security.cert.CertificateException;
import java.util.Date;
import java.util.Optional;

public class StaticServerMain {
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
                .route("GET", "/*", (request, client, next) -> {
                    File root = new File("static");
                    File f = new File(root, request.resource.substring(1));

                    if (f.getAbsolutePath().startsWith(root.getAbsolutePath())) {
                        FileInputStream fis = new FileInputStream(f);

                        return HttpServer.responseBuilder()
                                .ok()
                                .httpVersion("HTTP/1.0")
                                .content(fis)
                                .header("Content-Type", Files.probeContentType(f.toPath()))
                                .build();
                    } else {
                        throw new HttpException(HttpServer.responseBuilder().code(400).status("Bad Request").httpVersion("HTTP/1.0").build(), "directory traversal detected");
                    }

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
