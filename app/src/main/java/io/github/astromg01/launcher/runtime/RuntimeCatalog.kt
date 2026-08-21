package io.github.astromg01.launcher.runtime

import android.os.Build

object RuntimeCatalog {
    private const val SOURCE = "AngelAuraMC/angelauramc-openjdk-build"
    private const val BASE_URL =
        "https://github.com/AngelAuraMC/angelauramc-openjdk-build/releases/download/download"

    private val supportedMajors = listOf(8, 17, 21, 25)

    private val sha256ByRuntime = mapOf(
        "17:arm" to "4a9134f1ebf6340dd855805d351712462cddab6f3c2684da7e7da10ccf06648d",
        "17:arm64" to "e162c860fe05ee4a4e4af7606437419879f6c748386a7b09fa77d10db6a64091",
        "17:x86" to "223a2d54606a9eb853c8451cf1d6bda1a8f09cb357f40b790140e57d45731ed7",
        "17:x86_64" to "893e27d2aed8b40407f29fe939e2a0f193e5d55f72892a303db634b0808a2b61",
        "21:arm" to "96c297487def64666e379a9a363d9955c05b1a0b091b0cf24af88359a66f394a",
        "21:arm64" to "8d41ec401ee59f7722df60ed991f81ad146e130452804bfdd8a05d3436f7bbfe",
        "21:x86" to "9b8c7d10c5f751acb3b33506593da44ece52a0b091b0cf24af88359a66f394a",
        "21:x86_64" to "cb88723961f5f9ad63afa1f212eb199816c27cabfd7dc66567bde1d8fb69713b",
        "25:arm" to "9ae13aee9cba7b2d2d8f40965061667e876d7380d866f37a992db3eff296ffb5",
        "25:arm64" to "d3eb7afe2240c26728a1bb440502c5f18ac3883e932d202dd7f0c9bcbbce4c37"
    )

    fun deviceArchitecture(): String? {
        return Build.SUPPORTED_ABIS.firstNotNullOfOrNull { abi ->
            when (abi.lowercase()) {
                "arm64-v8a" -> "arm64"
                "armeabi-v7a", "armeabi" -> "arm"
                "x86_64" -> "x86_64"
                "x86" -> "x86"
                else -> null
            }
        }
    }

    fun availableMajors(): List<Int> {
        val arch = deviceArchitecture() ?: return emptyList()
        return supportedMajors.filter { major -> supports(major, arch) }
    }

    fun descriptor(majorVersion: Int): RuntimeDescriptor? {
        val arch = deviceArchitecture() ?: return null
        if (!supports(majorVersion, arch)) return null

        val fileName = "jre${majorVersion}-android-${arch}.tar.xz"
        return RuntimeDescriptor(
            majorVersion = majorVersion,
            architecture = arch,
            url = "$BASE_URL/$fileName",
            sha256 = sha256ByRuntime["$majorVersion:$arch"],
            source = SOURCE
        )
    }

    private fun supports(majorVersion: Int, architecture: String): Boolean {
        if (majorVersion !in supportedMajors) return false
        if (majorVersion == 25 && architecture == "x86") return false
        return architecture in setOf("arm", "arm64", "x86", "x86_64")
    }
}
