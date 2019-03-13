package org.junit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

public class Assert {
  public static void assertTrue(String message, boolean condition) {
    assertThat(condition).overridingErrorMessage(message).isTrue();
  }

  public static void assertTrue(boolean condition) {
    assertThat(condition).isTrue();
  }

  public static void assertFalse(String message, boolean condition) {
    assertThat(condition).overridingErrorMessage(message).isFalse();
  }

  public static void assertFalse(boolean condition) {
    assertThat(condition).isFalse();
  }

  public static void assertEquals(String message, Object expected, Object actual) {
    assertThat(actual).overridingErrorMessage(message).isEqualTo(expected);
  }

  public static void assertEquals(Object expected, Object actual) {
    assertThat(actual).isEqualTo(expected);
  }

  public static void assertNotEquals(String message, Object unexpected, Object actual) {
    assertThat(actual).overridingErrorMessage(message).isNotEqualTo(unexpected);
  }

  public static void assertNotEquals(Object unexpected, Object actual) {
    assertThat(actual).isNotEqualTo(unexpected);
  }

  public static void assertNotEquals(String message, long unexpected, long actual) {
    assertThat(actual).overridingErrorMessage(message).isNotEqualTo(unexpected);
  }

  public static void assertNotEquals(long unexpected, long actual) {
    assertThat(actual).isNotEqualTo(unexpected);
  }

  public static void assertNotEquals(
      String message, double unexpected, double actual, double delta) {
    assertThat(actual).overridingErrorMessage(message).isNotCloseTo(unexpected, offset(delta));
  }

  public static void assertNotEquals(double unexpected, double actual, double delta) {
    assertThat(actual).isNotCloseTo(unexpected, offset(delta));
  }

  public static void assertNotEquals(float unexpected, float actual, float delta) {
    assertThat(actual).isNotCloseTo(unexpected, offset(delta));
  }

  public static void assertEquals(String message, double expected, double actual, double delta) {
    assertThat(actual).overridingErrorMessage(message).isCloseTo(expected, offset(delta));
  }

  public static void assertEquals(String message, float expected, float actual, float delta) {
    assertThat(actual).overridingErrorMessage(message).isCloseTo(expected, offset(delta));
  }

  public static void assertNotEquals(String message, float unexpected, float actual, float delta) {
    assertThat(actual).overridingErrorMessage(message).isNotCloseTo(unexpected, offset(delta));
  }

  public static void assertEquals(long expected, long actual) {
    assertThat(actual).isEqualTo(expected);
  }

  public static void assertEquals(String message, long expected, long actual) {
    assertThat(actual).overridingErrorMessage(message).isEqualTo(expected);
  }

  public static void assertEquals(double expected, double actual) {
    assertThat(actual).isEqualTo(expected);
  }

  public static void assertEquals(String message, double expected, double actual) {
    assertThat(actual).overridingErrorMessage(message).isEqualTo(expected);
  }

  public static void assertEquals(double expected, double actual, double delta) {
    assertThat(actual).isCloseTo(expected, offset(delta));
  }

  public static void assertEquals(float expected, float actual, float delta) {
    assertThat(actual).isCloseTo(expected, offset(delta));
  }

  public static void assertNotNull(String message, Object object) {
    assertThat(object).overridingErrorMessage(message).isNotNull();
  }

  public static void assertNotNull(Object object) {
    assertThat(object).isNotNull();
  }

  public static void assertNull(String message, Object object) {
    assertThat(object).overridingErrorMessage(message).isNull();
  }

  public static void assertNull(Object object) {
    assertThat(object).isNull();
  }

  public static void assertSame(String message, Object expected, Object actual) {
    assertThat(actual).overridingErrorMessage(message).isSameAs(expected);
  }

  public static void assertSame(Object expected, Object actual) {
    assertThat(actual).isSameAs(expected);
  }

  public static void assertNotSame(String message, Object unexpected, Object actual) {
    assertThat(actual).overridingErrorMessage(message).isNotSameAs(unexpected);
  }

  public static void assertNotSame(Object unexpected, Object actual) {
    assertThat(actual).isNotSameAs(unexpected);
  }

  public static void assertEquals(String message, Object[] expecteds, Object[] actuals) {
    assertThat(actuals).overridingErrorMessage(message).containsExactly(expecteds);
  }

  public static void assertEquals(Object[] expecteds, Object[] actuals) {
    assertThat(actuals).containsExactly(expecteds);
  }
}