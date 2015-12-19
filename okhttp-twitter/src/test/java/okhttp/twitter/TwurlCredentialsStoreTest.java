package okhttp.twitter;

import java.io.File;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class TwurlCredentialsStoreTest {
  @Test
  public void testReadDefaultCredentials() {
    File file =
        new File(TwurlCredentialsStoreTest.class.getResource("/single_twurlrc.yaml").getFile());
    TwurlCredentialsStore store = new TwurlCredentialsStore(file);

    TwitterCredentials credentials = store.readDefaultCredentials();

    assertEquals("PROFILE", credentials.username);
    assertEquals("CONSUMER_KEY", credentials.consumerKey);
    assertEquals("CONSUMER_SECRET", credentials.consumerSecret);
    assertEquals("1234-TOKEN", credentials.token);
    assertEquals("SECRET", credentials.secret);
  }
}
