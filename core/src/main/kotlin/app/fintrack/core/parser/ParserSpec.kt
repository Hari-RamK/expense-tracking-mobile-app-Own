package app.fintrack.core.parser

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class TemplateSpec(
    val id: String,
    val eventKind: String,
    val direction: String? = null,
    val method: String,
    val accountKindHint: String? = null,
    val typeHint: String? = null,
    val isTransaction: Boolean = true,
    val dateFormat: String? = null,
    val dateAs: String? = null,
    val regex: String,
)

@Serializable
data class IgnoreSpec(val id: String, val reason: String, val regex: String)

@Serializable
data class TemplatesFile(
    val macros: Map<String, String> = emptyMap(),
    val templates: List<TemplateSpec>,
    val ignore: List<IgnoreSpec> = emptyList(),
)

@Serializable
data class BrandSpec(val institution: String, val kind: String)

@Serializable
data class SendersFile(
    val brands: Map<String, BrandSpec> = emptyMap(),
    val muted: List<String> = emptyList(),
)

data class ParserSpec(val templates: TemplatesFile, val senders: SendersFile) {
    /**
     * Adds the owner's own choices on top of the bundled spec: brands they mapped to a bank, senders they muted,
     * and "ignore messages like this" patterns. The bundled files stay untouched.
     */
    fun withOverlay(
        extraBrands: Map<String, BrandSpec> = emptyMap(),
        extraMuted: Collection<String> = emptyList(),
        extraIgnorePatterns: List<IgnoreSpec> = emptyList(),
    ): ParserSpec = ParserSpec(
        templates.copy(ignore = templates.ignore + extraIgnorePatterns),
        senders.copy(brands = senders.brands + extraBrands, muted = (senders.muted + extraMuted).distinct()),
    )

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Loads the spec bundled from /parser-spec (templates.json and senders.json). */
        fun loadBundled(): ParserSpec {
            fun read(name: String): String {
                val stream = checkNotNull(ParserSpec::class.java.getResourceAsStream("/parser-spec/$name")) {
                    "Missing bundled resource parser-spec/$name"
                }
                return stream.use { it.readBytes().toString(Charsets.UTF_8) }
            }
            return ParserSpec(
                templates = json.decodeFromString(read("templates.json")),
                senders = json.decodeFromString(read("senders.json")),
            )
        }
    }
}
