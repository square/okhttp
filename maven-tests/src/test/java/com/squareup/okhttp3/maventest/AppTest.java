package com.squareup.okhttp3.maventest;

import org.junit.Test;

import java.io.IOException;

/**
 * Unit test for simple App.
 */
public class AppTest {

  @Test
  public void testApp() throws IOException {
    new SampleHttpClient().makeCall();
  }
}
