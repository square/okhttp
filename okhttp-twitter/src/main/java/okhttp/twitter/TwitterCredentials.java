package okhttp.twitter;

public class TwitterCredentials {
  public String username;
  public String consumerKey;
  public String consumerSecret;
  public String token;
  public String secret;

  public TwitterCredentials() {
  }

  public TwitterCredentials(String username, String consumerKey, String consumerSecret,
      String token, String secret) {
    this.username = username;
    this.consumerKey = consumerKey;
    this.consumerSecret = consumerSecret;
    this.token = token;
    this.secret = secret;
  }

  @Override public String toString() {
    return "TwitterCredentials{"
        + "username='"
        + username
        + '\''
        + ", consumerKey='"
        + consumerKey
        + '\''
        +
        ", consumerSecret='"
        + consumerSecret
        + '\''
        + ", token='"
        + token
        + '\''
        + ", secret='"
        + secret
        + '\''
        + '}';
  }
}
