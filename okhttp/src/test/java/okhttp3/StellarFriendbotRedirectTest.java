package okhttp3;

import okhttp3.logging.HttpLoggingInterceptor;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class StellarFriendbotRedirectTest {

  @Test
  public void redirectSucceeds() throws Exception {
    OkHttpClient client = new OkHttpClient.Builder()
        //.followRedirects(true)
        //.followSslRedirects(true)
        //.addInterceptor(new HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.HEADERS))
        //    .addInterceptor(new HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.BODY))
        .build();

    Request request = new Request.Builder()
        .url(HttpUrl.parse(
            "https://horizon-testnet.stellar.org/friendbot?addr=GDORQAYCNBJFIRG2FWEXTH4X73EUCBE7P7T3TYOW2XG52HYU43L2S2CC"))
        .build();

    Response response = client.newCall(request).execute();
    String bodyText = response.body().string();
    assertThat(bodyText).contains("createAccountAlreadyExist");
    assertThat(bodyText).doesNotContain("horizon_version");
    assertThat(response.code()).isEqualTo(400);
  }
}
