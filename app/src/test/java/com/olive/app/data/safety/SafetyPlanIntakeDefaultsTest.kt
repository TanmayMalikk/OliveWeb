// Tests onboarding safety-plan questionnaire serialization, tailored checklist generation, and safe merging.
package com.olive.app.data.safety

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import com.olive.app.R
import com.olive.app.data.account.AbuseType
import com.olive.app.data.account.DependantNeed
import com.olive.app.data.account.FinancialSituation
import com.olive.app.data.account.ImmigrationStatus
import com.olive.app.data.account.SafetyCircumstance
import com.olive.app.data.account.SafetyPlanIntake
import com.olive.app.data.account.TransportSituation
import com.olive.app.data.account.UserProfile
import com.olive.app.data.account.profileChanges
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.w3c.dom.Element

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SafetyPlanIntakeDefaultsTest {
    private val context: Context by lazy { buildXmlBackedContext(RuntimeEnvironment.getApplication()) }

    @Test
    fun profileAndMalformedStoredJsonHandleSafetyIntakeSafely() {
        val intake = SafetyPlanIntake(
            completed = true,
            abuseTypes = setOf(AbuseType.DIGITAL_OR_STALKING, AbuseType.FINANCIAL),
            immigrationStatus = ImmigrationStatus.TEMPORARY_OR_SPONSORED,
            dependants = setOf(DependantNeed.CHILDREN, DependantNeed.PETS),
            financialSituation = FinancialSituation.SHARED_OR_MONITORED,
            transportSituation = TransportSituation.PUBLIC_TRANSIT,
            circumstances = setOf(
                SafetyCircumstance.LEGAL_OR_COURT_ORDERS,
                SafetyCircumstance.PRESCRIPTIONS_OR_MEDICAL,
            ),
        )
        val profile = UserProfile(username = "test_user", safetyIntake = intake)
        assertEquals(profile, UserProfile.fromJson(profile.toJson()))
        assertTrue(profile.toMap().containsKey("safetyIntake"))

        // Legacy profiles without a safetyIntake field restore default values.
        val legacy = UserProfile.fromJson(JSONObject().put("username", "legacy_user"))
        assertEquals(SafetyPlanIntake(), legacy.safetyIntake)

        // Unknown enum values and malformed array rows do not throw.
        val malformed = UserProfile.fromJson(
            JSONObject()
                .put("username", "future_user")
                .put(
                    "safetyIntake",
                    JSONObject()
                        .put("completed", true)
                        .put("abuseTypes", JSONArray().put("PHYSICAL").put("UNKNOWN_ABUSE").put(7))
                        .put("immigrationStatus", "UNKNOWN_FUTURE_STATUS")
                        .put("dependants", JSONArray().put("CHILDREN").put("FUTURE_TYPE").put(42))
                        .put("financialSituation", "BAD_VALUE")
                        .put("transportSituation", 99)
                        .put("circumstances", "not-an-array"),
                ),
        )
        assertTrue(malformed.safetyIntake.completed)
        assertEquals(setOf(AbuseType.PHYSICAL), malformed.safetyIntake.abuseTypes)
        assertEquals(ImmigrationStatus.PREFER_NOT_TO_SAY, malformed.safetyIntake.immigrationStatus)
        assertEquals(setOf(DependantNeed.CHILDREN), malformed.safetyIntake.dependants)
        assertEquals(FinancialSituation.PREFER_NOT_TO_SAY, malformed.safetyIntake.financialSituation)
        assertEquals(TransportSituation.PREFER_NOT_TO_SAY, malformed.safetyIntake.transportSituation)
        assertTrue(malformed.safetyIntake.circumstances.isEmpty())

        // Audit logs note questionnaire updates without writing raw immigration/financial/abuse answers.
        val changes = profileChanges(legacy, profile)
        assertEquals(1, changes.size)
        assertEquals("Safety plan questionnaire", changes.single().field)
        assertEquals("Updated", changes.single().after)
        assertFalse(changes.single().after.contains("DIGITAL_OR_STALKING"))
        assertFalse(changes.single().after.contains("TEMPORARY_OR_SPONSORED"))
        assertFalse(changes.single().after.contains("SHARED_OR_MONITORED"))
    }

    @Test
    fun unconfiguredIntakeKeepsLegacyDefaultsAndAffectedPeopleRule() {
        val plain = safetyPlanDefaultsFromIntake(context, SafetyPlanIntake(), hasAffectedPeople = false)
        val withAffected = safetyPlanDefaultsFromIntake(context, SafetyPlanIntake(), hasAffectedPeople = true)

        val plainDocs = readSafetyChecklist(plain.getValue("primary_documents")).map { it.text }
        val affectedDocs = readSafetyChecklist(withAffected.getValue("primary_documents")).map { it.text }
        assertEquals("Driver’s licence / government-issued photo ID", plainDocs.first())
        assertFalse(context.getString(R.string.safety_documents_dependants) in plainDocs)
        assertTrue(context.getString(R.string.safety_documents_dependants) in affectedDocs)
    }

    @Test
    fun immigrationStatusAndDependantsTailorDocumentsAndSubsections() {
        val temporaryWithKids = SafetyPlanIntake(
            completed = true,
            immigrationStatus = ImmigrationStatus.TEMPORARY_OR_SPONSORED,
            dependants = setOf(DependantNeed.CHILDREN, DependantNeed.PETS),
            financialSituation = FinancialSituation.SHARED_OR_MONITORED,
            transportSituation = TransportSituation.PUBLIC_TRANSIT,
            circumstances = setOf(SafetyCircumstance.LEGAL_OR_COURT_ORDERS),
        )
        val tailored = safetyPlanDefaultsFromIntake(context, temporaryWithKids)
        val docs = readSafetyChecklist(tailored.getValue("primary_documents")).map { it.text }
        val grab = readSafetyChecklist(tailored.getValue("primary_grab")).map { it.text }
        val finances = readSafetyChecklist(tailored.getValue("primary_finances")).map { it.text }
        val destination = readSafetyChecklist(tailored.getValue("primary_destination")).map { it.text }
        val travel = readSafetyChecklist(tailored.getValue("primary_travel")).map { it.text }
        val aftercare = readSafetyChecklist(tailored.getValue("primary_aftercare")).map { it.text }

        assertEquals(context.getString(R.string.safety_doc_photo_id), docs.first())
        assertTrue(context.getString(R.string.safety_doc_permit_sponsorship) in docs)
        assertTrue(context.getString(R.string.safety_doc_immigration_support) in docs)
        assertTrue(context.getString(R.string.safety_doc_children_records) in docs)
        assertTrue(context.getString(R.string.safety_doc_pet_records) in docs)
        assertTrue(context.getString(R.string.safety_doc_shared_banking) in docs)
        assertTrue(context.getString(R.string.safety_doc_court_orders) in docs)
        assertFalse(context.getString(R.string.safety_doc_refugee_papers) in docs)
        assertFalse(context.getString(R.string.safety_doc_medical_records) in docs)

        assertTrue(context.getString(R.string.safety_grab_separate_cash) in grab)
        assertTrue(context.getString(R.string.safety_grab_children) in grab)
        assertTrue(context.getString(R.string.safety_grab_pets) in grab)
        assertFalse(context.getString(R.string.safety_grab_house_and_car_keys) in grab)
        assertFalse(context.getString(R.string.safety_grab_medicines) in grab)

        assertTrue(context.getString(R.string.safety_fin_shared_private_account) in finances)
        assertTrue(context.getString(R.string.safety_fin_children_benefits) in finances)
        assertTrue(context.getString(R.string.safety_fin_newcomer_support) in finances)

        assertTrue(context.getString(R.string.safety_dest_children) in destination)
        assertTrue(context.getString(R.string.safety_dest_pets) in destination)

        assertTrue(context.getString(R.string.safety_travel_transit_routes) in travel)
        assertTrue(context.getString(R.string.safety_travel_children) in travel)
        assertTrue(context.getString(R.string.safety_travel_pets) in travel)

        assertTrue(context.getString(R.string.safety_after_immigration_legal) in aftercare)
        assertTrue(context.getString(R.string.safety_after_children_school) in aftercare)
        assertTrue(context.getString(R.string.safety_after_legal_aid) in aftercare)
        assertFalse(context.getString(R.string.safety_after_medical) in aftercare)
    }

    @Test
    fun citizenIndependentWithOwnCarReceivesDistinctRelevantItems() {
        val intake = SafetyPlanIntake(
            completed = true,
            immigrationStatus = ImmigrationStatus.CITIZEN,
            financialSituation = FinancialSituation.INDEPENDENT,
            transportSituation = TransportSituation.OWN_VEHICLE,
            circumstances = setOf(SafetyCircumstance.PRESCRIPTIONS_OR_MEDICAL),
        )
        val tailored = safetyPlanDefaultsFromIntake(context, intake)
        val docs = readSafetyChecklist(tailored.getValue("primary_documents")).map { it.text }
        val grab = readSafetyChecklist(tailored.getValue("primary_grab")).map { it.text }
        val finances = readSafetyChecklist(tailored.getValue("primary_finances")).map { it.text }
        val travel = readSafetyChecklist(tailored.getValue("primary_travel")).map { it.text }

        assertTrue(context.getString(R.string.safety_doc_citizen_passport) in docs)
        assertTrue(context.getString(R.string.safety_doc_independent_banking) in docs)
        assertTrue(context.getString(R.string.safety_doc_medical_records) in docs)
        assertFalse(context.getString(R.string.safety_doc_children_records) in docs)
        assertFalse(context.getString(R.string.safety_doc_permit_sponsorship) in docs)

        assertTrue(context.getString(R.string.safety_grab_house_and_car_keys) in grab)
        assertTrue(context.getString(R.string.safety_grab_medicines) in grab)
        assertTrue(context.getString(R.string.safety_fin_indep_passwords) in finances)
        assertTrue(context.getString(R.string.safety_travel_vehicle_fuel) in travel)
    }

    @Test
    fun maximumSelectionsRespectBackendSectionCharLimitsAndMergePreservesUserEdits() {
        val maxIntake = SafetyPlanIntake(
            completed = true,
            abuseTypes = AbuseType.entries.toSet(),
            immigrationStatus = ImmigrationStatus.REFUGEE_OR_CLAIMANT,
            dependants = DependantNeed.entries.toSet(),
            financialSituation = FinancialSituation.NO_INDEPENDENT_ACCESS,
            transportSituation = TransportSituation.NEEDS_RIDE_SUPPORT,
            circumstances = SafetyCircumstance.entries.toSet(),
        )
        val defaults = safetyPlanDefaultsFromIntake(context, maxIntake, hasAffectedPeople = true)
        defaults.forEach { (key, value) ->
            assertTrue("Section $key exceeded 4000-char server limit (${value.length})", value.length <= 4000)
        }

        val initial = writeSafetyChecklist(
            listOf(
                SafetyChecklistItem("existing-1", "Passport or citizenship certificate", checked = true, attachmentIds = listOf("doc-1")),
                SafetyChecklistItem("custom-1", "Keep spare keys with neighbour", checked = false),
            ),
        )
        val merged = mergeTailoredChecklistItems(
            initial,
            listOf(
                "passport or citizenship certificate",
                context.getString(R.string.safety_doc_pet_records),
                context.getString(R.string.safety_doc_court_orders),
            ),
        )
        val items = readSafetyChecklist(merged)
        assertEquals(4, items.size)
        assertTrue(items[0].checked)
        assertEquals(listOf("doc-1"), items[0].attachmentIds)
        assertEquals("Keep spare keys with neighbour", items[1].text)
        assertEquals(context.getString(R.string.safety_doc_pet_records), items[2].text)
        assertEquals(context.getString(R.string.safety_doc_court_orders), items[3].text)
        assertTrue(merged.length <= 4000)
    }

    @Test
    fun abuseTypesTailorDocumentsGrabFinancesDestinationTravelAndAftercare() {
        val intake = SafetyPlanIntake(
            completed = true,
            abuseTypes = setOf(
                AbuseType.PHYSICAL,
                AbuseType.FINANCIAL,
                AbuseType.DIGITAL_OR_STALKING,
            ),
        )
        val tailored = safetyPlanDefaultsFromIntake(context, intake)
        val docs = readSafetyChecklist(tailored.getValue("primary_documents")).map { it.text }
        val grab = readSafetyChecklist(tailored.getValue("primary_grab")).map { it.text }
        val finances = readSafetyChecklist(tailored.getValue("primary_finances")).map { it.text }
        val destination = readSafetyChecklist(tailored.getValue("primary_destination")).map { it.text }
        val travel = readSafetyChecklist(tailored.getValue("primary_travel")).map { it.text }
        val aftercare = readSafetyChecklist(tailored.getValue("primary_aftercare")).map { it.text }

        assertTrue(context.getString(R.string.safety_doc_abuse_physical_evidence) in docs)
        assertTrue(context.getString(R.string.safety_doc_abuse_financial_records) in docs)
        assertFalse(context.getString(R.string.safety_doc_abuse_sexual_records) in docs)
        assertFalse(context.getString(R.string.safety_doc_abuse_immigration_coercion) in docs)

        assertTrue(context.getString(R.string.safety_grab_abuse_digital_check) in grab)
        assertTrue(context.getString(R.string.safety_fin_abuse_credit_check) in finances)
        assertTrue(context.getString(R.string.safety_dest_abuse_physical_exit) in destination)
        assertTrue(context.getString(R.string.safety_travel_abuse_digital_tracking) in travel)
        assertTrue(context.getString(R.string.safety_after_abuse_physical_care) in aftercare)
        assertTrue(context.getString(R.string.safety_after_abuse_digital_security) in aftercare)
        assertFalse(context.getString(R.string.safety_after_abuse_sexual_support) in aftercare)
    }

    @Test
    fun allFiveLocalesDefineEverySafetyChecklistQuestionnaireString() {
        val resDir = resolveResDir()
        val englishKeys = parseXmlStrings(File(resDir, "values/safety_checklist.xml")).first.keys
        listOf("values-fr", "values-es", "values-ar", "values-zh-rCN").forEach { localeDir ->
            val localeKeys = File(resDir, localeDir)
                .listFiles { file -> file.extension == "xml" }
                .orEmpty()
                .flatMapTo(mutableSetOf()) { file -> parseXmlStrings(file).first.keys }
            val missing = englishKeys - localeKeys
            assertTrue("Missing keys in $localeDir: $missing", missing.isEmpty())
        }
    }

    private fun buildXmlBackedContext(base: Context): Context {
        val resDir = resolveResDir()
        val stringsByName = mutableMapOf<String, String>()
        val arraysByName = mutableMapOf<String, Array<String>>()
        listOf("values/strings.xml", "values/safety_checklist.xml").forEach { rel ->
            val (parsedStrings, parsedArrays) = parseXmlStrings(File(resDir, rel))
            stringsByName += parsedStrings
            arraysByName += parsedArrays
        }
        val stringNamesById = R.string::class.java.fields.associate { it.getInt(null) to it.name }
        val arrayNamesById = R.array::class.java.fields.associate { it.getInt(null) to it.name }

        val mockResources = Mockito.spy(base.resources)
        Mockito.doAnswer { invocation ->
            val id = invocation.getArgument<Int>(0)
            val name = stringNamesById[id]
            stringsByName[name] ?: throw Resources.NotFoundException("String resource $name ($id) not found")
        }.`when`(mockResources).getString(anyInt())
        Mockito.doAnswer { invocation ->
            val id = invocation.getArgument<Int>(0)
            val name = arrayNamesById[id]
            arraysByName[name] ?: throw Resources.NotFoundException("Array resource $name ($id) not found")
        }.`when`(mockResources).getStringArray(anyInt())

        return object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getResources(): Resources = mockResources
        }
    }

    private fun resolveResDir(): File =
        listOf(File("app/src/main/res"), File("src/main/res")).first { it.isDirectory }

    private fun parseXmlStrings(file: File): Pair<Map<String, String>, Map<String, Array<String>>> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val strings = mutableMapOf<String, String>()
        val stringNodes = document.getElementsByTagName("string")
        for (i in 0 until stringNodes.length) {
            val element = stringNodes.item(i) as Element
            strings[element.getAttribute("name")] = element.textContent
        }
        val arrays = mutableMapOf<String, Array<String>>()
        val arrayNodes = document.getElementsByTagName("string-array")
        for (i in 0 until arrayNodes.length) {
            val element = arrayNodes.item(i) as Element
            val itemNodes = element.getElementsByTagName("item")
            arrays[element.getAttribute("name")] = Array(itemNodes.length) { idx ->
                itemNodes.item(idx).textContent
            }
        }
        return strings to arrays
    }
}
