package okhttp3.survey.ssllabs

import retrofit2.http.GET

interface SslLabsService {
    @GET("getClients")
    suspend fun clients(): List<UserAgentCapabilities>

    companion object {
        const val BASE_URL = "https://api.ssllabs.com/api/v3/"
    }
}
