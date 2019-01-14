package okhttp3;

/**
 * Status code as defined by
 * <a href="http://tools.ietf.org/html/rfc6455#section-7.4">
 * Section 7.4 of RFC 6455
 * </a>.
 */
public class WebSocketStatusCode {
    /**
     * indicates a normal closure, meaning whatever purpose the
     * connection was established for has been fulfilled.
     */
    public static final int NORMAL = 1000;
    /**
     * 1001 indicates that an endpoint is "going away", such as a server
     * going down, or a browser having navigated away from a page.
     */
    public static final int GOING_AWAY = 1001;
    /**
     * 1002 indicates that an endpoint is terminating the connection due
     * to a protocol error.
     */
    public static final int PROTOCOL_ERROR = 1002;
    /**
     * 1003 indicates that an endpoint is terminating the connection
     * because it has received a type of data it cannot accept (e.g. an
     * endpoint that understands only text data MAY send this if it
     * receives a binary message).
     */
    public static final int REFUSE = 1003;
    /*1004: Reserved. The specific meaning might be defined in the future.*/
    /**
     * 1005 is a reserved value and MUST NOT be set as a status code in a
     * Close control frame by an endpoint. It is designated for use in
     * applications expecting a status code to indicate that no status
     * code was actually present.
     */
    public static final int NOCODE = 1005;
    /**
     * 1006 is a reserved value and MUST NOT be set as a status code in a
     * Close control frame by an endpoint. It is designated for use in
     * applications expecting a status code to indicate that the
     * connection was closed abnormally, e.g. without sending or
     * receiving a Close control frame.
     */
    public static final int ABNORMAL_CLOSE = 1006;
    /**
     * 1007 indicates that an endpoint is terminating the connection
     * because it has received data within a message that was not
     * consistent with the type of the message (e.g., non-UTF-8 [RFC3629]
     * data within a text message).
     */
    public static final int NO_UTF8 = 1007;
    /**
     * 1008 indicates that an endpoint is terminating the connection
     * because it has received a message that violates its policy. This
     * is a generic status code that can be returned when there is no
     * other more suitable status code (e.g. 1003 or 1009), or if there
     * is a need to hide specific details about the policy.
     */
    public static final int POLICY_VALIDATION = 1008;
    /**
     * 1009 indicates that an endpoint is terminating the connection
     * because it has received a message which is too big for it to
     * process.
     */
    public static final int TOOBIG = 1009;
    /**
     * 1010 indicates that an endpoint (client) is terminating the
     * connection because it has expected the server to negotiate one or
     * more extension, but the server didn't return them in the response
     * message of the WebSocket handshake. The list of extensions which
     * are needed SHOULD appear in the /reason/ part of the Close frame.
     * Note that this status code is not used by the server, because it
     * can fail the WebSocket handshake instead.
     */
    public static final int EXTENSION = 1010;
    /**
     * 1011 indicates that a server is terminating the connection because
     * it encountered an unexpected condition that prevented it from
     * fulfilling the request.
     **/
    public static final int UNEXPECTED_CONDITION = 1011;
    /**
     * 1012 indicates that the service is restarted.
     * A client may reconnect, and if it choses to do, should reconnect using a randomized delay of 5 - 30s.
     * See https://www.ietf.org/mail-archive/web/hybi/current/msg09670.html for more information.
     *
     * @since 1.3.8
     **/
    public static final int SERVICE_RESTART = 1012;
    /**
     * 1013 indicates that the service is experiencing overload.
     * A client should only connect to a different IP (when there are multiple for the target)
     * or reconnect to the same IP upon user action.
     * See https://www.ietf.org/mail-archive/web/hybi/current/msg09670.html for more information.
     **/
    public static final int TRY_AGAIN_LATER = 1013;
    /**
     * 1014 indicates that the server was acting as a gateway or proxy and received an
     * invalid response from the upstream server. This is similar to 502 HTTP Status Code
     * See https://www.ietf.org/mail-archive/web/hybi/current/msg10748.html fore more information.
     **/
    public static final int BAD_GATEWAY = 1014;
    /**
     * 1015 is a reserved value and MUST NOT be set as a status code in a
     * Close control frame by an endpoint. It is designated for use in
     * applications expecting a status code to indicate that the
     * connection was closed due to a failure to perform a TLS handshake
     * (e.g., the server certificate can't be verified).
     **/
    public static final int TLS_ERROR = 1015;
}
