package okhttp.twitter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategy;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.File;
import java.io.IOException;

public class TwurlCredentialsStore implements CredentialsStore {
  private final File file;

  public TwurlCredentialsStore(File file) {
    this.file = file;
  }

  public TwurlRc readTwurlRc() {
    ObjectMapper objectMapper = new ObjectMapper(new YAMLFactory());
    objectMapper.setPropertyNamingStrategy(PropertyNamingStrategy.CAMEL_CASE_TO_LOWER_CASE_WITH_UNDERSCORES);

    try {
      return objectMapper.readValue(file, TwurlRc.class);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public TwitterCredentials readDefaultCredentials() {
    TwurlRc twurlRc = readTwurlRc();

    String username = twurlRc.defaultProfile().get(0);
    String consumerKey = twurlRc.defaultProfile().get(1);

    return twurlRc.readCredentials(username, consumerKey);
  }

  public TwitterCredentials readCredentials(String username, String consumerKey) {
    TwurlRc twurlRc = readTwurlRc();

    return twurlRc.readCredentials(username, consumerKey);
  }
}
