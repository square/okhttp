package com.squareup.okhttp.mockwebserver.exchanges;

import com.squareup.okhttp.mockwebserver.RecordedRequest;

import java.nio.charset.Charset;
import java.util.List;

/**
 * Helper class of predicates that can be used to describe the request that should be matched using
 * the {@link MatchingRecordedRequest#with(Predicate)} method.
 */
public final class RequestMatchers {

    private RequestMatchers() {
        // utility class
    }

    static Predicate<RecordedRequest> request(final String method,
                                              final Predicate<String> pathMatcher) {
        return new Predicate<RecordedRequest>() {
            @Override
            public boolean test(final RecordedRequest recordedRequest) {
                return method.equals(recordedRequest.getMethod())
                        && pathMatcher.test(recordedRequest.getPath());
            }
        };
    }

    /**
     * Constructs a predicate matching a GET request for the given path.
     * @param path The path to match.
     * @return The predicate.
     */
    public static Predicate<RecordedRequest> get(String path) {
        return get(new EqualityPredicate<>(path));
    }

    /**
     * Constructs a predicate matching a GET request with a path matching the given predicate.
     * @param pathMatcher The matcher to test the path with.
     * @return The predicate.
     */
    public static Predicate<RecordedRequest> get(final Predicate<String> pathMatcher) {
        return request("GET", pathMatcher);
    }

    /**
     * Constructs a predicate matching a POST request for the given path.
     * @param path The path to match.
     * @return The predicate.
     */
    public static Predicate<RecordedRequest> post(String path) {
        return post(new EqualityPredicate<>(path));
    }

    /**
     * Constructs a predicate matching a POST request with a path matching the given predicate.
     * @param pathMatcher The matcher to test the path with.
     * @return The predicate.
     */
    public static Predicate<RecordedRequest> post(final Predicate<String> pathMatcher) {
        return request("POST", pathMatcher);
    }

    /**
     * Constructs a predicate matching a PUT request for the given path.
     * @param path The path to match.
     * @return The predicate.
     */
    public static Predicate<RecordedRequest> put(String path) {
        return put(new EqualityPredicate<>(path));
    }

    /**
     * Constructs a predicate matching a PUT request with a path matching the given predicate.
     * @param pathMatcher The matcher to test the path with.
     * @return The predicate.
     */
    public static Predicate<RecordedRequest> put(final Predicate<String> pathMatcher) {
        return request("PUT", pathMatcher);
    }

    /**
     * Constructs a predicate matching a DELETE request for the given path.
     * @param path The path to match.
     * @return The predicate.
     */
    public static Predicate<RecordedRequest> delete(String path) {
        return delete(new EqualityPredicate<>(path));
    }

    /**
     * Constructs a predicate matching a DELETE request with a path matching the given predicate.
     * @param pathMatcher The matcher to test the path with.
     * @return The predicate.
     */
    public static Predicate<RecordedRequest> delete(final Predicate<String> pathMatcher) {
        return request("DELETE", pathMatcher);
    }

    /**
     * Constructs a predicate matching a HEAD request for the given path.
     * @param path The path to match.
     * @return The predicate.
     */
    public static Predicate<RecordedRequest> head(String path) {
        return head(new EqualityPredicate<>(path));
    }

    /**
     * Constructs a predicate matching a HEAD request with a path matching the given predicate.
     * @param pathMatcher The matcher to test the path with.
     * @return The predicate.
     */
    public static Predicate<RecordedRequest> head(final Predicate<String> pathMatcher) {
        return request("HEAD", pathMatcher);
    }

    /**
     * Constructs a predicate matching a PATH request for the given path.
     * @param path The path to match.
     * @return The predicate.
     */
    public static Predicate<RecordedRequest> patch(String path) {
        return patch(new EqualityPredicate<>(path));
    }

    /**
     * Constructs a predicate matching a PATCH request with a path matching the given predicate.
     * @param pathMatcher The matcher to test the path with.
     * @return The predicate.
     */
    public static Predicate<RecordedRequest> patch(final Predicate<String> pathMatcher) {
        return request("PATCH", pathMatcher);
    }

    /**
     * Constructs a predicate matching an OPTIONS request for the given path.
     * @param path The path to match.
     * @return The predicate.
     */
    public static Predicate<RecordedRequest> options(String path) {
        return options(new EqualityPredicate<>(path));
    }

    /**
     * Constructs a predicate matching an OPTIONS request with a path matching the given predicate.
     * @param pathMatcher The matcher to test the path with.
     * @return The predicate.
     */
    public static Predicate<RecordedRequest> options(final Predicate<String> pathMatcher) {
        return request("OPTIONS", pathMatcher);
    }

    /**
     * Constructs a predicate matching a TRACE request for the given path.
     * @param path The path to match.
     * @return The predicate.
     */
    public static Predicate<RecordedRequest> trace(String path) {
        return trace(new EqualityPredicate<>(path));
    }

    /**
     * Constructs a predicate matching a TRACE request with a path matching the given predicate.
     * @param pathMatcher The matcher to test the path with.
     * @return The predicate.
     */
    public static Predicate<RecordedRequest> trace(final Predicate<String> pathMatcher) {
        return request("TRACE", pathMatcher);
    }

    /**
     * Constructs a predicate matching a CONNECT request for the given path.
     * @param path The path to match.
     * @return The predicate.
     */
    public static Predicate<RecordedRequest> connect(String path) {
        return connect(new EqualityPredicate<>(path));
    }

    /**
     * Constructs a predicate matching a CONNECT request with a path matching the given predicate.
     * @param pathMatcher The matcher to test the path with.
     * @return The predicate.
     */
    public static Predicate<RecordedRequest> connect(final Predicate<String> pathMatcher) {
        return request("CONNECT", pathMatcher);
    }

    /**
     * Matches a request when the given <code>bodyMatcher</code> matches the body of a request
     * which has been passed as <code>UTF-8</code>.
     * @param bodyMatcher Used to match the body of a request; should assume that the body has been
     *                    passed as <code>UTF-8</code>.
     * @return Something that will match a request when the <code>bodyMatcher</code> also matches.
     */
    public static Predicate<RecordedRequest> body(final Predicate<String> bodyMatcher) {
        return new Predicate<RecordedRequest>() {
            @Override
            public boolean test(final RecordedRequest recordedRequest) {
                final String contentType = recordedRequest.getHeader("Content-Encoding");

                if (contentType != null) {
                    return bodyMatcher.test(recordedRequest.getBody()
                            .readString(Charset.forName(contentType)));
                }
                return bodyMatcher.test(recordedRequest.getBody().readUtf8());
            }
        };
    }

    /**
     * Matches a request when there is only one header of name <code>name</code> and which also has
     * a value which matches the given <code>valueMatcher</code>.
     * @param name Name that the header should have.
     * @param valueMatcher Value that the header should match against.
     * @return Something that will match a request which has a header '<code>name</code>' with only
     * one value and that matches <code>valueMatcher</code>.
     */
    public static Predicate<RecordedRequest> header(final String name,
                                                    final Predicate<String> valueMatcher) {
        return headers(name, new Predicate<List<String>>() {
            @Override
            public boolean test(final List<String> strings) {
                return strings.size() == 1 && valueMatcher.test(strings.get(0));
            }
        });
    }

    /**
     * Matches a request when there is a header of name <code>name</code> and which also has
     * values that match the given <code>valuesMatcher</code>.
     * @param name Name that the header should have.
     * @param valuesMatcher Values that the header should match against.
     * @return Something that will match a request which has a header '<code>name</code>' with
     * values that match <code>valuesMatcher</code>.
     */
    public static Predicate<RecordedRequest> headers(final String name,
                                                     final Predicate<List<String>> valuesMatcher) {
        return new Predicate<RecordedRequest>() {
            @Override
            public boolean test(final RecordedRequest recordedRequest) {
                final List<String> headers = recordedRequest.getHeaders().values(name);
                return valuesMatcher.test(headers);
            }
        };
    }

}
