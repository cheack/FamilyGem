package app.familygem.geni.api

import com.google.gson.annotations.SerializedName

/** Geni profile, only the fields used so far. */
data class GeniProfile(
    val id: String,
    val guid: String?,
    @SerializedName("first_name") val firstName: String?,
    @SerializedName("last_name") val lastName: String?,
    val name: String?,
    @SerializedName("updated_at") val updatedAt: String?
)
