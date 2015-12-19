package okhttp.twitter;

public interface CredentialsStore {
  TwitterCredentials readDefaultCredentials();
}
