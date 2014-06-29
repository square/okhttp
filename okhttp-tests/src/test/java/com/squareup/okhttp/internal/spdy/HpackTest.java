package com.squareup.okhttp.internal.spdy;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.stream.JsonReader;
import okio.Buffer;
import org.bouncycastle.util.encoders.Hex;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.*;

/**
 * Tests Hpack implementation using https://github.com/http2jp/hpack-test-case/
 */
@RunWith(Parameterized.class)
public class HpackTest {

  private static final String STORY_RESOURCE_FORMAT =
      "/hpack-test-case/%s/story_%02d.json";
  private static final String[] INTEROP_TESTS = { "go-hpack", "haskell-http2-diff",
      "haskell-http2-diff-huffman", "haskell-http2-linear", "haskell-http2-linear-huffman",
      "haskell-http2-naive", "haskell-http2-naive-huffman", "haskell-http2-static",
      "haskell-http2-static-huffman", "hyper-hpack", "nghttp2", "nghttp2-16384-4096",
      "nghttp2-change-table-size", "node-http2-hpack", "node-http2-protocol", "twitter-hpack" };

  private static final class Story {
    private List<Case> cases;
    private int draft;
    private String description;
  }

  private static final class Case {
    private int seqno;
    private String wire;
    private List<Map<String, String>> headers;

    public List<Header> getHeaders() {
      List<Header> result = new ArrayList<>();
      for (Map<String, String> inputHeader : headers) {
        Map.Entry<String, String> entry = inputHeader.entrySet().iterator().next();
        result.add(new Header(entry.getKey(), entry.getValue()));
      }
      return result;
    }

    public byte[] getBytes() {
      return Hex.decode(wire);
    }
  }

  private static final Gson GSON = new GsonBuilder().create();

  @Parameters(name="{0}")
  public static Collection<String[]> allInteropTestNames() {
    List<String[]> result = new ArrayList<>();
    for (String interopTestName : INTEROP_TESTS) {
      for (int i = 0; i < 100; i++) { // break after last test.
        String storyResourceName = String.format(STORY_RESOURCE_FORMAT, interopTestName, i);
        if (HpackTest.class.getResource(storyResourceName) == null) {
          break;
        }
        result.add(new String[] { storyResourceName });
      }
    }
    return result;
  }

  private final Buffer bytesIn = new Buffer();
  private HpackDraft08.Reader hpackReader;
  private Buffer bytesOut = new Buffer();
  private HpackDraft08.Writer hpackWriter;

  private final String storyResourceName;

  public HpackTest(String storyResourceName) {
    this.storyResourceName = storyResourceName;
  }

  private Story readStory(InputStream jsonResource) {
    return GSON.fromJson(new InputStreamReader(jsonResource), Story.class);
  }

  @Before
  public void reset() {
    hpackReader = new HpackDraft08.Reader(4096, bytesIn);
    hpackWriter = new HpackDraft08.Writer(bytesOut);
  }

  @Test
  public void testDecoderInterop() throws Exception {

    Story story = readStory(getClass().getResourceAsStream(storyResourceName));
    for (Case caze : story.cases) {
      bytesIn.write(Hex.decode(caze.wire));
      hpackReader.readHeaders();
      hpackReader.emitReferenceSet();
      assertEqualsIgnoreOrder(String.format("decode seqno=%d", caze.seqno), caze.getHeaders(),
          hpackReader.getAndReset());
    }
  }

  private static void assertEqualsIgnoreOrder(String message, List<?> expected, List<?> observed) {
    assertEquals(message, expected.size(), observed.size());
    assertEquals(message, new HashSet<>(expected), new HashSet<>(observed));
  }

  public void testEncodeDecode() throws Exception {
    Story story = readStory(getClass().getResourceAsStream(storyResourceName));
    for (Case caze : story.cases) {
      hpackWriter.writeHeaders(caze.getHeaders());
      bytesIn.write(bytesOut.readByteArray());
      hpackReader.readHeaders();
      hpackReader.emitReferenceSet();
      assertEqualsIgnoreOrder(String.format("roundtrip seqno=%d", caze.seqno), caze.getHeaders(),
          hpackReader.getAndReset());
    }
  }
}
