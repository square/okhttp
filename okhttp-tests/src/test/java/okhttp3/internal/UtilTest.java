package okhttp3.internal;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public class UtilTest {
  @Test
  public void testAssertionError() {
    NullPointerException nullPointerException = new NullPointerException();
    AssertionError ae = Util.assertionError("npe", nullPointerException);
    assertSame(nullPointerException, ae.getCause());
    assertEquals("npe", ae.getMessage());
  }
}
