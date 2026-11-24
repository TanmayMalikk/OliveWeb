// Safe-app users: stores onboarding safety-plan questionnaire answers and serializes them safely.
package com.olive.app.data.account

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive

enum class ImmigrationStatus {
    PREFER_NOT_TO_SAY,
    CITIZEN,
    PERMANENT_RESIDENT,
    TEMPORARY_OR_SPONSORED,
    REFUGEE_OR_CLAIMANT,
    UNDOCUMENTED_OR_UNCERTAIN,
}

enum class DependantNeed {
    CHILDREN,
    ELDER_OR_ADULT_DEPENDANT,
    PETS,
}

enum class FinancialSituation {
    PREFER_NOT_TO_SAY,
    INDEPENDENT,
    SHARED_OR_MONITORED,
    NO_INDEPENDENT_ACCESS,
}

enum class TransportSituation {
    PREFER_NOT_TO_SAY,
    OWN_VEHICLE,
    PUBLIC_TRANSIT,
    NEEDS_RIDE_SUPPORT,
}

enum class SafetyCircumstance {
    LEGAL_OR_COURT_ORDERS,
    MARRIAGE_OR_PARENTING,
    PRESCRIPTIONS_OR_MEDICAL,
    SHARED_LEASE_OR_MORTGAGE,
}

enum class AbuseType {
    PHYSICAL,
    EMOTIONAL_OR_PSYCHOLOGICAL,
    FINANCIAL,
    DIGITAL_OR_STALKING,
    SEXUAL,
    CULTURAL_OR_IMMIGRATION,
}

private open class SafeEnumSerializer<T : Enum<T>>(
    serialName: String,
    private val defaultValue: T,
    private val parse: (String) -> T?,
) : KSerializer<T> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(serialName, PrimitiveKind.STRING)

    /**
     * Serializes the enum entry by its name.
     *
     * @param encoder The serialization encoder that receives the value.
     * @param value The enum option to store by name.
     */
    override fun serialize(encoder: Encoder, value: T) = encoder.encodeString(value.name)

    /**
     * Deserializes the enum entry, falling back to [defaultValue] when unrecognized.
     *
     * @param decoder The serialization decoder that supplies the stored value.
     * @return The decoded enum constant, or the configured fallback when the stored name is unknown.
     */
    override fun deserialize(decoder: Decoder): T {
        val raw = (decoder as? JsonDecoder)?.let { json ->
            (json.decodeJsonElement() as? JsonPrimitive)?.takeIf { it.isString }?.content
        } ?: runCatching { decoder.decodeString() }.getOrNull()
        return raw?.let(parse) ?: defaultValue
    }
}

private open class SafeEnumSetSerializer<T : Enum<T>>(
    serialName: String,
    private val parse: (String) -> T?,
) : KSerializer<Set<T>> {
    override val descriptor: SerialDescriptor = SetSerializer(String.serializer()).descriptor

    /**
     * Serializes the enum set as a sorted list of enum names.
     *
     * @param encoder The serialization encoder that receives the value.
     * @param value The selected enum options to store as names.
     */
    override fun serialize(encoder: Encoder, value: Set<T>) =
        encoder.encodeSerializableValue(ListSerializer(String.serializer()), value.map { it.name }.sorted())

    /**
     * Deserializes a JSON array of enum names into a set, ignoring unknown entries.
     *
     * @param decoder The serialization decoder that supplies the stored value.
     * @return The set of recognized options from the stored enum names.
     */
    override fun deserialize(decoder: Decoder): Set<T> {
        val json = decoder as? JsonDecoder
            ?: return decoder.decodeSerializableValue(ListSerializer(String.serializer())).mapNotNull(parse).toSet()
        val array = json.decodeJsonElement() as? JsonArray ?: return emptySet()
        return array.mapNotNull { element ->
            val name = (element as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapNotNull null
            parse(name)
        }.toSet()
    }
}

/**
 * Returns the enum constant matching [name], or null when unrecognized.
 *
 * @param T The enum type to search for a matching constant.
 * @param name The stored enum constant name to look up.
 * @return The matching enum constant, or null when the name is unknown.
 */
private inline fun <reified T : Enum<T>> enumOrNull(name: String): T? =
    enumValues<T>().firstOrNull { it.name == name }

object ImmigrationStatusSerializer : KSerializer<ImmigrationStatus> by SafeEnumSerializer(
    "ImmigrationStatus",
    ImmigrationStatus.PREFER_NOT_TO_SAY,
    ::enumOrNull,
)

object FinancialSituationSerializer : KSerializer<FinancialSituation> by SafeEnumSerializer(
    "FinancialSituation",
    FinancialSituation.PREFER_NOT_TO_SAY,
    ::enumOrNull,
)

object TransportSituationSerializer : KSerializer<TransportSituation> by SafeEnumSerializer(
    "TransportSituation",
    TransportSituation.PREFER_NOT_TO_SAY,
    ::enumOrNull,
)

object DependantNeedSetSerializer : KSerializer<Set<DependantNeed>> by SafeEnumSetSerializer(
    "DependantNeedSet",
    ::enumOrNull,
)

object SafetyCircumstanceSetSerializer : KSerializer<Set<SafetyCircumstance>> by SafeEnumSetSerializer(
    "SafetyCircumstanceSet",
    ::enumOrNull,
)

object AbuseTypeSetSerializer : KSerializer<Set<AbuseType>> by SafeEnumSetSerializer(
    "AbuseTypeSet",
    ::enumOrNull,
)

/**
 * Private onboarding questionnaire answers that tailor the user's initial safety-plan documents and steps.
 */
@Serializable
data class SafetyPlanIntake(
    val completed: Boolean = false,
    @Serializable(with = AbuseTypeSetSerializer::class)
    val abuseTypes: Set<AbuseType> = emptySet(),
    @Serializable(with = ImmigrationStatusSerializer::class)
    val immigrationStatus: ImmigrationStatus = ImmigrationStatus.PREFER_NOT_TO_SAY,
    @Serializable(with = DependantNeedSetSerializer::class)
    val dependants: Set<DependantNeed> = emptySet(),
    @Serializable(with = FinancialSituationSerializer::class)
    val financialSituation: FinancialSituation = FinancialSituation.PREFER_NOT_TO_SAY,
    @Serializable(with = TransportSituationSerializer::class)
    val transportSituation: TransportSituation = TransportSituation.PREFER_NOT_TO_SAY,
    @Serializable(with = SafetyCircumstanceSetSerializer::class)
    val circumstances: Set<SafetyCircumstance> = emptySet(),
) {
    val hasAnySelection: Boolean
        get() = abuseTypes.isNotEmpty() ||
            immigrationStatus != ImmigrationStatus.PREFER_NOT_TO_SAY ||
            dependants.isNotEmpty() ||
            financialSituation != FinancialSituation.PREFER_NOT_TO_SAY ||
            transportSituation != TransportSituation.PREFER_NOT_TO_SAY ||
            circumstances.isNotEmpty()

    /**
     * Converts questionnaire answers into a plain map for structured Firebase profile storage.
     *
     * @return Map containing the serialized safety-intake fields.
     */
    fun toMap(): Map<String, Any> = mapOf(
        "completed" to completed,
        "abuseTypes" to abuseTypes.map { it.name }.sorted(),
        "immigrationStatus" to immigrationStatus.name,
        "dependants" to dependants.map { it.name }.sorted(),
        "financialSituation" to financialSituation.name,
        "transportSituation" to transportSituation.name,
        "circumstances" to circumstances.map { it.name }.sorted(),
    )
}
