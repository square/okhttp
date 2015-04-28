package com.squareup.okhttp.mockwebserver.exchanges;

import com.squareup.okhttp.Headers;
import com.squareup.okhttp.mockwebserver.RecordedRequest;
import okio.Buffer;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Collections;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RequestMatchersTest {

  @Test
  public void requestShouldMatchWhenMethodAndPathAreTheSame() throws Exception {
    final String method = "oneString";
    final String path = "anotherString";

    assertTrue(RequestMatchers.request(method, new EqualityPredicate<>(path)).test(new RecordedRequest(method + " " + path + " ", Headers.of(), Collections.<Integer>emptyList(), 1, new Buffer(), 1, null)));
  }

  @Test
  public void requestShouldNotMatchWhenMethodIsDifferent() throws Exception {
    final String path = "randomPath";

    assertFalse(RequestMatchers.request("anotherPath", new EqualityPredicate<>(path)).test(new RecordedRequest("evenMore" + " " + path + " ", Headers.of(), Collections.<Integer>emptyList(), 1, new Buffer(), 1, null)));
  }

  @Test
  public void requestShouldNotMatchWhenPathIsDifferent() throws Exception {
    final String method = "asdasdas";

    assertFalse(RequestMatchers.request(method, new EqualityPredicate<>("iDoNotKnow")).test(new RecordedRequest(method + " " + "texdsaflkjasdflj" + " ", Headers.of(), Collections.<Integer>emptyList(), 1, new Buffer(), 1, null)));
  }

  @Test
  public void bodyShouldMatchWhenPredicateMatches() {
    final String body = "something or other";

    assertTrue(RequestMatchers.body(new EqualityPredicate<>(body)).test(new RecordedRequest(null, Headers.of(), Collections.<Integer>emptyList(), 1, new Buffer().writeString(body, StandardCharsets.UTF_8), 1, null)));
  }

  @Test
  public void bodyShouldNotMatchWhenPredicateDoesNotMatch() {
    assertFalse(RequestMatchers.body(new EqualityPredicate<>("hello, world")).test(new RecordedRequest(null, Headers.of(), Collections.<Integer>emptyList(), 1, new Buffer().writeString("another string", StandardCharsets.UTF_8), 1, null)));
  }

  @Test
  public void headerShouldMatchWhenNameAndValueMatch() {
    final String name = "name";
    final String value = "value";

    assertTrue(RequestMatchers.header(name, new EqualityPredicate<>(value)).test(new RecordedRequest(null, Headers.of(name, value), Collections.<Integer>emptyList(), 1, new Buffer(), 1, null)));
  }

  @Test
  public void headerShouldNotMatchWhenNameDoesNotMatch() {
    final String value = "value";

    assertFalse(RequestMatchers.header("expectedName", new EqualityPredicate<>(value)).test(new RecordedRequest(null, Headers.of("differentName", value), Collections.<Integer>emptyList(), 1, new Buffer(), 1, null)));
  }

  @Test
  public void headerShouldNotMatchWhenValueDoesNotMatch() {
    final String name = "name";

    assertFalse(RequestMatchers.header(name, new EqualityPredicate<>("expectedValue")).test(new RecordedRequest(null, Headers.of(name, "differentValue"), Collections.<Integer>emptyList(), 1, new Buffer(), 1, null)));
  }

}
