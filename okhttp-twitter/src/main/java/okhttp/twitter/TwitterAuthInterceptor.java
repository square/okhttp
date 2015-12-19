package okhttp.twitter;

import com.google.common.base.Joiner;
import com.twitter.joauth.Normalizer;
import com.twitter.joauth.OAuthParams;
import com.twitter.joauth.Signer;
import com.twitter.joauth.UrlCodec;
import com.twitter.joauth.keyvalue.KeyValueHandler;
import com.twitter.joauth.keyvalue.KeyValueParser;
import java.io.IOException;
import java.nio.charset.Charset;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import okhttp3.FormBody;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.Buffer;

public class TwitterAuthInterceptor implements Interceptor {
  private final SecureRandom secureRandom = new SecureRandom();

  public static final Set<String> TWITTER_API_HOSTS =
      Collections.unmodifiableSet(new HashSet<String>(
          Arrays.asList("api.twitter.com", "upload.twitter.com", "stream.twitter.com",
              "mobile.twitter.com", "syndication.twitter.com")
      ));

  private final TwitterCredentials credentials;

  public TwitterAuthInterceptor(TwitterCredentials credentials) {
    this.credentials = credentials;
  }

  public boolean requiresTwitterAuth(Request request) {
    String host = request.url().host();

    return TWITTER_API_HOSTS.contains(host);
  }

  @Override public Response intercept(Chain chain) throws IOException {
    Request request = chain.request();

    if (requiresTwitterAuth(request)) {
      try {
        request =
            request.newBuilder().addHeader("Authorization", generateAuthorization(request)).build();
      } catch (NoSuchAlgorithmException e) {
        throw new IOException(e);
      } catch (InvalidKeyException e) {
        throw new IOException(e);
      }
    }

    return chain.proceed(request);
  }

  private String quoted(String str) {
    return "\"" + str + "\"";
  }

  private long generateTimestamp() {
    long timestamp = System.currentTimeMillis();
    return timestamp / 1000;
  }

  private String generateNonce() {
    return Long.toString(Math.abs(secureRandom.nextLong())) + System.currentTimeMillis();
  }

  private String generateAuthorization(Request request)
      throws NoSuchAlgorithmException, InvalidKeyException, IOException {
    long timestampSecs = generateTimestamp();
    String nonce = generateNonce();

    Normalizer normalizer = Normalizer.getStandardNormalizer();
    Signer signer = Signer.getStandardSigner();

    OAuthParams.OAuth1Params oAuth1Params = new OAuthParams.OAuth1Params(
        credentials.token, credentials.consumerKey, nonce, timestampSecs,
        Long.toString(timestampSecs), "", OAuthParams.HMAC_SHA1, OAuthParams.ONE_DOT_OH
    );

    List<com.twitter.joauth.Request.Pair> javaParams = new ArrayList<>();

    Set<String> queryParamNames = request.url().queryParameterNames();
    for (String queryParam : queryParamNames) {
      List<String> values = request.url().queryParameterValues(queryParam);

      for (String value : values) {
        javaParams.add(new com.twitter.joauth.Request.Pair(UrlCodec.encode(queryParam),
            UrlCodec.encode(value)));
      }
    }

    if (request.method().equals("POST")) {
      System.out.println(request.header("Content-Type"));

      RequestBody body = request.body();

      if (body instanceof FormBody) {
        FormBody formBody = (FormBody) body;

        for (int i = 0; i < formBody.size(); i++) {
          javaParams.add(new com.twitter.joauth.Request.Pair(formBody.encodedName(i),
              formBody.encodedValue(i)));
        }
      } else if (isFormContentType(request)) {
        Buffer buffer = new Buffer();
        body.writeTo(buffer);
        String encodedBody = buffer.readString(Charset.forName("UTF-8"));

        KeyValueHandler.DuplicateKeyValueHandler handler =
            new KeyValueHandler.DuplicateKeyValueHandler();

        KeyValueParser.StandardKeyValueParser bodyParser =
            new KeyValueParser.StandardKeyValueParser("&", "=");
        bodyParser.parse(encodedBody, Arrays.<KeyValueHandler>asList(handler));

        System.out.println(handler.toList());

        javaParams.addAll(handler.toList());
      }
    }

    String normalized = normalizer.normalize(
        request.isHttps() ? "https" : "http", request.url().host(), request.url().port(),
        request.method(), request.url().encodedPath(), javaParams, oAuth1Params
    );

    String signature = signer.getString(normalized, credentials.secret, credentials.consumerSecret);

    Map<String, String> oauthHeaders = new HashMap<>();
    oauthHeaders.put(OAuthParams.OAUTH_CONSUMER_KEY, quoted(credentials.consumerKey));
    oauthHeaders.put(OAuthParams.OAUTH_TOKEN, quoted(credentials.token));
    oauthHeaders.put(OAuthParams.OAUTH_SIGNATURE, quoted(signature));
    oauthHeaders.put(OAuthParams.OAUTH_SIGNATURE_METHOD, quoted(OAuthParams.HMAC_SHA1));
    oauthHeaders.put(OAuthParams.OAUTH_TIMESTAMP, quoted(Long.toString(timestampSecs)));
    oauthHeaders.put(OAuthParams.OAUTH_NONCE, quoted(nonce));
    oauthHeaders.put(OAuthParams.OAUTH_VERSION, quoted(OAuthParams.ONE_DOT_OH));

    return "OAuth " + Joiner.on(", ").withKeyValueSeparator("=").join(oauthHeaders);
  }

  private boolean isFormContentType(Request request) {
    // TODO should we consider case? or parse properly?
    String contentType = request.header("Content-Type");
    return contentType != null && contentType.startsWith("application/x-www-form-urlencoded");
  }
}
