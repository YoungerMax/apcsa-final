import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

public class ExtendedHttpServer extends HttpServer {
    public final LinkedList<Binding> bindings;
    public final LinkedList<ErrorHandler> errorHandlers;

    public ExtendedHttpServer(SocketAddress socketAddress, SSLContext sslContext) {
        super(socketAddress, sslContext);

        this.bindings = new LinkedList<>();
        this.errorHandlers = new LinkedList<>();
    }

    public ExtendedHttpServer(SocketAddress socketAddress) {
        this(socketAddress, null);
    }

    public ExtendedHttpServer binding(Binding binding) {
        this.bindings.push(binding);
        return this;
    }

    public ExtendedHttpServer binding(Matcher matcher, Handler handler) {
        return this.binding(new Binding(matcher, handler));
    }

    public ExtendedHttpServer middleware(Handler handler) {
        return this.binding((requestedMethod, requestedResource) -> true, handler);
    }

    public ExtendedHttpServer route(String method, String resource, Handler handler) {
        return this.binding(new PatternMatcher(method, resource), handler);
    }

    public ExtendedHttpServer errorHandler(ErrorHandler errorHandler) {
        this.errorHandlers.push(errorHandler);
        return this;
    }

    @Override
    public Response handle(Request request, Socket client) throws Exception {
        AtomicInteger index = new AtomicInteger(0);
        NextSupplier nextSupplier = new NextSupplier(request, client, this.bindings, index);
        Response response = nextSupplier.get();

        if (response == null)
            throw new HttpException(status(404, "Not Found", "HTTP/1.0"), "Unhandled route: " + request.resource);

        return response;
    }

    @Override
    public Response handleError(Socket client, Exception exception) {
        for (ErrorHandler errorHandler : this.errorHandlers) {
            try {
                Response response = errorHandler.handle(exception, client);

                if (response != null) {
                    return response;
                }
            } catch (Exception e) {
                break;
            }
        }

        exception.printStackTrace();

        if (exception instanceof HttpException httpException && httpException.response != null) {
            return httpException.response;
        }

        return status(500, "Internal Server Error", "HTTP/1.0");
    }

    @FunctionalInterface
    public interface Handler {
        Response handle(Request request, Socket client, Supplier<Response> next) throws Exception;
    }

    @FunctionalInterface
    public interface ErrorHandler {
        Response handle(Exception exception, Socket client);
    }

    public static class NextSupplier implements Supplier<Response> {
        private final Request request;
        private final Socket client;
        private final LinkedList<Binding> bindings;
        private final AtomicInteger handlerIndex;

        public NextSupplier(Request request, Socket client, LinkedList<Binding> bindings, AtomicInteger handlerIndex) {
            this.request = request;
            this.client = client;
            this.bindings = bindings;
            this.handlerIndex = handlerIndex;
        }

        @Override
        public Response get() {
            try {
                while (this.bindings.size() > this.handlerIndex.get()) {
                    Binding binding = this.bindings.get(this.handlerIndex.getAndIncrement());

                    if (binding.matcher.matches(this.request.method, this.request.resource)) {
                        return binding.routeHandler.handle(this.request, this.client, this);
                    }
                }

                return null;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    @FunctionalInterface
    public interface Matcher {
        boolean matches(String requestedMethod, String requestedResource);
    }

    public record PatternMatcher(String method, String pattern) implements Matcher {
        @Override
        public boolean matches(String requestedMethod, String requestedResource) {
            if (!this.method.equalsIgnoreCase(requestedMethod)) {
                return false;
            }

            int requestedMatchingIdx = 0;
            int patternIdx = 0;

            while (this.pattern.length() > patternIdx) {
                char patternChar = this.pattern.charAt(patternIdx);

                switch (patternChar) {
                    case '*':
                        if (this.pattern.length() > patternIdx + 1) {
                            char next = this.pattern.charAt(patternIdx + 1);
                            int nextIdx = requestedResource.indexOf(next, requestedMatchingIdx);

                            if (nextIdx < 0) {
                                return false;
                            } else {
                                requestedMatchingIdx = nextIdx + 1;
                                patternIdx++;
                            }
                        } else {
                            return true;
                        }

                        break;

                    default:
                        if (requestedMatchingIdx < requestedResource.length() && patternChar == requestedResource.charAt(requestedMatchingIdx)) {
                            requestedMatchingIdx++;
                        } else {
                            return false;
                        }
                }

                patternIdx++;
            }

            return requestedResource.length() == requestedMatchingIdx;
        }
    }

    public record Binding(Matcher matcher, Handler routeHandler) {

    }
}
