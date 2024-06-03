public class HttpException extends RuntimeException {
    public final HttpServer.Response response;

    public HttpException(HttpServer.Response response, String message) {
        super(message + " (" + response.code + " " + response.status + ")");

        this.response = response;
    }
}
