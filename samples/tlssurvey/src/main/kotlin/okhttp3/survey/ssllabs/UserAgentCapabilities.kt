package okhttp3.survey.ssllabs

import com.squareup.moshi.JsonClass


@JsonClass(generateAdapter = true)
class UserAgentCapabilities(
    val abortsOnUnrecognizedName: Boolean,
    val alpnProtocols: List<String>,
    val ellipticCurves: List<Int>,
    val handshakeFormat: String,
    val hexHandshakeBytes: String,
    val highestProtocol: Int,
    val id: Int,
    val isGrade0: Boolean,
    val lowestProtocol: Int,
    val maxDhBits: Int,
    val maxRsaBits: Int,
    val minDhBits: Int,
    val minEcdsaBits: Int,
    val minRsaBits: Int,
    val name: String,
    val npnProtocols: List<String>,
    val platform: String?,
    val requiresSha2: Boolean,
    val signatureAlgorithms: List<Int>,
    val suiteIds: List<Int>,
    val suiteNames: List<String>,
    val supportsCompression: Boolean,
    val supportsNpn: Boolean,
    val supportsRi: Boolean,
    val supportsSni: Boolean,
    val supportsStapling: Boolean,
    val supportsTickets: Boolean,
    val userAgent: String?,
    val version: String
)
