package tn.loukious.facebookappadsremover

import android.app.Activity
import android.os.Bundle
import android.view.View
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.MatchType
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.MethodData
import org.json.JSONObject
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.ArrayDeque
import java.util.IdentityHashMap
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

internal val STORY_AD_PROVIDER_TAGS = listOf(
    "ads_deletion",
    "ads_insertion",
    "StoryAdsInDisc"
)

internal val FB571_STORY_AD_SOURCE_CLASSES = listOf(
    "X.9xH",
    "X.A4W",
    "X.9zi",
    "X.A4w",
    "X.CNo",
    "X.KJw"
)

internal val FB571_FEED_CSR_CLASSES = listOf(
    "X.21p",
    "X.baJ",
    "X.baK"
)

internal val FB571_FEED_ITEM_CONTRACT_CLASSES = listOf(
    "X.3YX"
)

internal const val FB571_NETWORK_FEED_CLASS = "X.1fM"
internal const val FB571_NETWORK_FEED_METHOD = "A0B"
internal const val FB571_SPONSORED_POOL_CLASS = "X.21O"
internal const val FB571_SPONSORED_POOL_ADD_METHOD = "A03"

internal val FB571_SURVIVING_FEED_TYPE_CLASSES = listOf(
    "X.2OT",
    "X.2OU",
    "X.2OP",
    "X.2Ou",
    "X.2Oc",
    "X.3OJ",
    "X.3OF",
    "X.3xW",
    GRAPHQL_FEED_UNIT_EDGE_CLASS
)

internal val FEED_AD_CATEGORY_VALUES = setOf(
    "SPONSORED",
    "PROMOTION",
    "ENGAGEMENT_QP",
    "AD",
    "ADVERTISEMENT",
    "BANNER"
)

internal val FEED_SAFE_CONTAINER_CATEGORY_VALUES = setOf(
    "FB_SHORTS",
    "MULTI_FB_STORIES_TRAY"
)

internal val FEED_AD_SIGNAL_TOKENS = listOf(
    "sponsored",
    "promotion",
    "multiads",
    "quickpromotion",
    "reels_banner_ad",
    "reelsbannerads",
    "reels_post_loop_deferred_card",
    "deferred_card",
    "adbreakdeferredcta",
    "instreamadidlewithbannerstate",
    "instream_legacy_banner_ad",
    "unified_player_banner_ad",
    "banner_ad_",
    "floatingcta"
)

internal val REELS_AD_SIGNAL_TOKENS = listOf(
    "sponsored",
    "promotion",
    "multiads",
    "quickpromotion",
    "reels_banner_ad",
    "reelsbannerads",
    "adbreakdeferredcta",
    "instreamadidlewithbannerstate",
    "instream_legacy_banner_ad",
    "unified_player_banner_ad",
    "banner_ad_"
)

internal data class FeedListSanitizerHook(
    val method: Method,
    val listArgIndex: Int
)

internal data class FeedCsrFilterHook(
    val method: Method,
    val listArgIndex: Int
)

internal data class StoryAdProviderHooks(
    val providerClass: Class<*>,
    val mergeMethod: Method?,
    val fetchMoreAdsMethod: Method?,
    val deferredUpdateMethod: Method?,
    val insertionTriggerMethod: Method?
)

internal val storyAdProviderClassesHooked = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
internal val feedCsrMethodsHooked = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
internal val lateFeedMethodsHooked = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
internal val sponsoredPoolMethodsHooked = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
internal val feedComponentMethodsHooked = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
internal val visibleAdTraceInstalled = AtomicInteger(0)
internal val visibleAdViewsTraced = ConcurrentHashMap<Int, Boolean>()
internal val survivingFeedAdTraceCount = AtomicInteger(0)
internal val survivingFeedTypeContractsLogged = AtomicInteger(0)

internal class AdStoryInspector(
    private val adKindEnumClass: Class<*>
) {
    private val enumMethodCache = ConcurrentHashMap<Class<*>, List<Method>>()
    private val fieldCache = ConcurrentHashMap<Class<*>, List<Field>>()
    private val allMethodsCache = ConcurrentHashMap<Class<*>, List<Method>>()

    fun containsAdStory(
        value: Any?,
        depth: Int = 0,
        seen: IdentityHashMap<Any, Boolean> = IdentityHashMap()
    ): Boolean {
        return containsAdKind(value, depth, seen) &&
            containsReelsAdSignal(value, 0, IdentityHashMap())
    }

    private fun containsAdKind(
        value: Any?,
        depth: Int = 0,
        seen: IdentityHashMap<Any, Boolean> = IdentityHashMap()
    ): Boolean {
        if (value == null || depth > 4) return false
        if (isAdKind(value)) return true

        val type = value.javaClass
        if (type.isPrimitive || value is String || value is Number || value is Boolean || value is CharSequence) {
            return false
        }
        if (seen.put(value, true) != null) return false

        if (value is Iterable<*>) {
            var checked = 0
            for (item in value) {
                if (containsAdKind(item, depth + 1, seen)) return true
                checked++
                if (checked >= 8) break
            }
        }

        if (type.isArray) {
            val array = value as? Array<*>
            if (array != null) {
                var checked = 0
                for (item in array) {
                    if (containsAdKind(item, depth + 1, seen)) return true
                    checked++
                    if (checked >= 8) break
                }
            }
        }

        for (method in enumMethodsFor(type)) {
            val marker = runCatching { method.invoke(value) }.getOrNull()
            if (isAdKind(marker)) return true
        }

        for (field in fieldsFor(type)) {
            val fieldValue = runCatching { field.get(value) }.getOrNull()
            if (containsAdKind(fieldValue, depth + 1, seen)) return true
        }

        return false
    }

    private fun containsReelsAdSignal(
        value: Any?,
        depth: Int,
        seen: IdentityHashMap<Any, Boolean>
    ): Boolean {
        if (value == null || depth > 4) return false

        if (value is CharSequence) {
            return isReelsAdSignalText(value.toString())
        }

        val type = value.javaClass
        if (isReelsAdSignalText(type.name)) return true

        if (type.isEnum) {
            return isReelsAdSignalText(value.toString())
        }

        if (type.isPrimitive || value is Number || value is Boolean) {
            return false
        }

        if (seen.put(value, true) != null) return false

        if (value is Iterable<*>) {
            var checked = 0
            for (item in value) {
                if (containsReelsAdSignal(item, depth + 1, seen)) return true
                checked++
                if (checked >= 8) break
            }
        }

        if (type.isArray) {
            val array = value as? Array<*>
            if (array != null) {
                var checked = 0
                for (item in array) {
                    if (containsReelsAdSignal(item, depth + 1, seen)) return true
                    checked++
                    if (checked >= 8) break
                }
            }
        }

        if (isReelsAdSignalText(runCatching { value.toString() }.getOrNull())) return true

        for (method in stringMethodsFor(type)) {
            val marker = runCatching { method.invoke(value) as? String }.getOrNull()
            if (isReelsAdSignalText(marker)) return true
        }

        for (field in fieldsFor(type)) {
            val fieldValue = runCatching { field.get(value) }.getOrNull()
            if (containsReelsAdSignal(fieldValue, depth + 1, seen)) return true
        }

        return false
    }

    private fun isAdKind(value: Any?): Boolean {
        return value != null && value.javaClass == adKindEnumClass && value.toString() == "AD"
    }

    private fun enumMethodsFor(type: Class<*>): List<Method> {
        return enumMethodCache.getOrPut(type) {
            val methods = LinkedHashMap<String, Method>()
            var current: Class<*>? = type
            while (current != null && current != Any::class.java) {
                current.declaredMethods.forEach { method ->
                    if (!Modifier.isStatic(method.modifiers) &&
                        method.parameterCount == 0 &&
                        method.returnType == adKindEnumClass
                    ) {
                        method.isAccessible = true
                        methods.putIfAbsent("${current.name}#${method.name}", method)
                    }
                }
                current = current.superclass
            }
            methods.values.toList()
        }
    }

    private fun fieldsFor(type: Class<*>): List<Field> {
        return fieldCache.getOrPut(type) {
            val fields = ArrayList<Field>()
            var current: Class<*>? = type
            while (current != null && current != Any::class.java && fields.size < 24) {
                current.declaredFields.forEach { field ->
                    if (!Modifier.isStatic(field.modifiers) && fields.size < 24) {
                        field.isAccessible = true
                        fields.add(field)
                    }
                }
                current = current.superclass
            }
            fields
        }
    }

    private fun stringMethodsFor(type: Class<*>): List<Method> {
        return allMethodsFor(type)
            .asSequence()
            .filter { method ->
                method.parameterCount == 0 &&
                    method.returnType == String::class.java &&
                    method.name != "toString"
            }
            .take(12)
            .onEach { method -> method.isAccessible = true }
            .toList()
    }

    private fun allMethodsFor(type: Class<*>): List<Method> {
        return allMethodsCache.getOrPut(type) {
            val methods = LinkedHashMap<String, Method>()
            var current: Class<*>? = type
            while (current != null && current != Any::class.java) {
                current.declaredMethods.forEach { method ->
                    if (!Modifier.isStatic(method.modifiers)) {
                        method.isAccessible = true
                        methods.putIfAbsent("${current.name}#${method.name}/${method.parameterCount}", method)
                    }
                }
                current = current.superclass
            }
            methods.values.toList()
        }
    }

    private fun isReelsAdSignalText(value: String?): Boolean {
        if (value.isNullOrBlank()) return false
        val normalized = value.lowercase()
        return REELS_AD_SIGNAL_TOKENS.any { token -> normalized.contains(token) }
    }
}

internal class FeedItemInspector(
    itemContractTypes: Collection<Class<*>>
) {
    private val itemModelAccessor =
        resolveItemContractAccessor(itemContractTypes, "B1P")
            ?: resolveItemContractAccessor(itemContractTypes, "B2r")
            ?: resolveItemModelAccessor(itemContractTypes)
    private val itemEdgeAccessor =
        resolveItemContractAccessor(itemContractTypes, "BDp")
            ?: resolveItemContractAccessor(itemContractTypes, "BG7")
            ?: resolveItemEdgeAccessor(itemContractTypes)
    private val itemNetworkAccessor =
        resolveItemContractAccessor(itemContractTypes, "AqM")
            ?: resolveItemContractAccessor(itemContractTypes, "ArH")
            ?: resolveItemNetworkAccessor(itemContractTypes)
    private val categoryMethodCache = ConcurrentHashMap<Class<*>, Method>()
    private val edgeAccessorCache = ConcurrentHashMap<Class<*>, Method>()
    private val edgeCategoryAccessorCache = ConcurrentHashMap<Class<*>, Method>()
    private val feedUnitAccessorCache = ConcurrentHashMap<Class<*>, Method>()
    private val backendDataAccessorCache = ConcurrentHashMap<Class<*>, Method>()
    private val typeNameMethodCache = ConcurrentHashMap<Class<*>, Method>()
    private val stringAccessorCache = ConcurrentHashMap<Class<*>, List<Method>>()
    private val stringFieldCache = ConcurrentHashMap<Class<*>, List<Field>>()

    private data class FeedItemFacts(
        val modelCategory: String?,
        val edgeCategory: String?,
        val network: Boolean?,
        val inflatedUnitClass: String?,
        val inflatedTypeName: String?,
        val backendUnitClass: String?,
        val backendTypeName: String?
    )

    fun isSponsoredFeedItem(value: Any?): Boolean {
        if (isDefinitelySponsoredFeedItem(value)) {
            return true
        }

        val model = invokeNoThrow(itemModelAccessor, value)
        val edge = edgeFrom(value)
        val feedUnit = feedUnitFrom(edge)
        val backendData = backendDataFrom(edge)

        if (containsKnownAdSignals(value)) return true
        if (containsKnownAdSignals(model)) return true
        if (containsKnownAdSignals(edge)) return true
        if (containsKnownAdSignals(feedUnit)) return true
        if (containsKnownAdSignals(backendData)) return true

        return false
    }

    fun isDefinitelySponsoredFeedItem(value: Any?): Boolean {
        if (value == null) return false

        val model = invokeNoThrow(itemModelAccessor, value)
        val modelCategory = readCategory(model)
        if (isSafeFeedContainerCategory(modelCategory)) {
            return false
        }
        if (isSponsoredFeedCategory(modelCategory)) {
            return true
        }

        val edge = edgeFrom(value)
        val edgeCategory = readEdgeCategory(edge) ?: readCategory(edge)
        if (isSafeFeedContainerCategory(edgeCategory)) {
            return false
        }
        if (isSponsoredFeedCategory(edgeCategory)) {
            return true
        }

        val feedUnit = feedUnitFrom(edge)
        val backendData = backendDataFrom(edge)
        val inflatedUnitClassName = feedUnit?.javaClass?.name
        val backendUnitClassName = backendData?.javaClass?.name
        if (
            inflatedUnitClassName == GRAPHQL_MULTI_ADS_FEED_UNIT_CLASS ||
            inflatedUnitClassName == GRAPHQL_QUICK_PROMO_FEED_UNIT_CLASS
        ) {
            return true
        }

        val typeName = readTypeName(feedUnit) ?: readTypeName(backendData)
        if (
            isLikelyAdTypeName(typeName) ||
            isAdSignalText(inflatedUnitClassName) ||
            isAdSignalText(backendUnitClassName)
        ) {
            return true
        }

        return false
    }

    fun storyPoolBlockReason(value: Any?): String? {
        return if (isDefinitelySponsoredFeedItem(value)) "strict" else null
    }

    fun describe(item: Any?): String {
        if (item == null) return "null"

        val facts = factsFor(item)
        val modelCategory = facts.modelCategory ?: "unknown"
        val edgeCategory = facts.edgeCategory ?: "unknown"
        val network = facts.network?.toString() ?: "unknown"
        val inflatedUnitClass = facts.inflatedUnitClass ?: "null"
        val inflatedTypeName = facts.inflatedTypeName ?: "unknown"
        val backendUnitClass = facts.backendUnitClass ?: "null"
        val backendTypeName = facts.backendTypeName ?: "unknown"

        return "modelCat=$modelCategory edgeCat=$edgeCategory isAd=${isSponsoredFeedItem(item)} network=$network wrapper=${item.javaClass.name} inflated=$inflatedUnitClass/$inflatedTypeName backend=$backendUnitClass/$backendTypeName"
    }

    private fun factsFor(item: Any?): FeedItemFacts {
        val model = invokeNoThrow(itemModelAccessor, item)
        val edge = edgeFrom(item)
        val feedUnit = feedUnitFrom(edge)
        val backendData = backendDataFrom(edge)
        return FeedItemFacts(
            modelCategory = readCategory(model),
            edgeCategory = readEdgeCategory(edge) ?: readCategory(edge),
            network = invokeNoThrow(itemNetworkAccessor, item) as? Boolean,
            inflatedUnitClass = feedUnit?.javaClass?.name,
            inflatedTypeName = readTypeName(feedUnit),
            backendUnitClass = backendData?.javaClass?.name,
            backendTypeName = readTypeName(backendData)
        )
    }

    private fun edgeFrom(value: Any?): Any? {
        if (value == null) return null
        if (value.javaClass.name == GRAPHQL_FEED_UNIT_EDGE_CLASS) return value

        invokeNoThrow(itemEdgeAccessor, value)?.let { directEdge ->
            if (directEdge.javaClass.name == GRAPHQL_FEED_UNIT_EDGE_CLASS) {
                return directEdge
            }
        }

        val fallback = cachedMethod(edgeAccessorCache, value.javaClass) {
            resolveChildAccessor(value) { candidateValue ->
                candidateValue != null && candidateValue.javaClass.name == GRAPHQL_FEED_UNIT_EDGE_CLASS
            }
        }
        return invokeNoThrow(fallback, value)
    }

    private fun feedUnitFrom(edge: Any?): Any? {
        if (edge == null) return null

        val accessor = cachedMethod(feedUnitAccessorCache, edge.javaClass) {
            resolveNamedNoArgAccessor(edge.javaClass, "BL9")
                ?: resolveNamedNoArgAccessor(edge.javaClass, "A03")
                ?: resolveChildAccessor(edge) { candidateValue ->
                    val className = candidateValue?.javaClass?.name
                    className == GRAPHQL_MULTI_ADS_FEED_UNIT_CLASS ||
                        className == GRAPHQL_QUICK_PROMO_FEED_UNIT_CLASS ||
                        readTypeName(candidateValue)?.let { it != "FeedUnitEdge" && it != "FeedBackendData" } == true
                }
        }
        return invokeNoThrow(accessor, edge)
    }

    private fun backendDataFrom(edge: Any?): Any? {
        if (edge == null) return null

        val accessor = cachedMethod(backendDataAccessorCache, edge.javaClass) {
            resolveNamedNoArgAccessor(edge.javaClass, "BL0")
                ?: resolveNamedNoArgAccessor(edge.javaClass, "A05")
                ?: resolveChildAccessor(edge) { candidateValue ->
                    readTypeName(candidateValue) == "FeedBackendData"
                }
        }
        return invokeNoThrow(accessor, edge)
    }

    private fun readEdgeCategory(value: Any?): String? {
        if (value == null) return null

        val accessor = cachedMethod(edgeCategoryAccessorCache, value.javaClass) {
            resolveNamedNoArgAccessor(value.javaClass, "B4k")
                ?: allInstanceMethods(value.javaClass).firstOrNull { candidate ->
                    candidate.parameterCount == 0 &&
                        candidate.returnType.isEnum &&
                        candidate.returnType.enumConstants?.any {
                            val name = it.toString()
                            name == "SPONSORED" || name == "PROMOTION"
                        } == true
                }?.apply { isAccessible = true }
        }
        return invokeNoThrow(accessor, value)?.toString()
    }

    private fun resolveItemContractAccessor(itemContractTypes: Collection<Class<*>>, methodName: String): Method? {
        return itemContractTypes
            .asSequence()
            .flatMap { type -> allInstanceMethods(type).asSequence() }
            .firstOrNull { candidate ->
                candidate.parameterCount == 0 && candidate.name == methodName
            }?.apply { isAccessible = true }
    }

    private fun resolveNamedNoArgAccessor(type: Class<*>, methodName: String): Method? {
        return allInstanceMethods(type).firstOrNull { candidate ->
            candidate.parameterCount == 0 && candidate.name == methodName
        }?.apply { isAccessible = true }
    }

    fun describeAccessors(): String {
        return "model=${accessorName(itemModelAccessor)} edge=${accessorName(itemEdgeAccessor)} network=${accessorName(itemNetworkAccessor)}"
    }

    private fun accessorName(method: Method?): String {
        return method?.let { "${it.declaringClass.name}.${it.name}" } ?: "unresolved"
    }

    private fun readCategory(value: Any?): String? {
        if (value == null) return null

        if (value.javaClass.isEnum) {
            return value.toString()
        }

        val accessor = cachedMethod(categoryMethodCache, value.javaClass) {
            allInstanceMethods(value.javaClass).firstOrNull { candidate ->
                candidate.parameterCount == 0 &&
                    candidate.returnType.isEnum &&
                    candidate.returnType.enumConstants?.any {
                        val name = it.toString()
                        name == "SPONSORED" || name == "PROMOTION"
                    } == true
            }?.apply { isAccessible = true }
        }
        return invokeNoThrow(accessor, value)?.toString()
    }

    private fun readTypeName(value: Any?): String? {
        if (value == null) return null

        val accessor = cachedMethod(typeNameMethodCache, value.javaClass) {
            resolveNamedNoArgAccessor(value.javaClass, "getTypeName")
                ?: allInstanceMethods(value.javaClass).firstOrNull { candidate ->
                    candidate.parameterCount == 0 &&
                        candidate.returnType == String::class.java &&
                        candidate.name == "getTypeName"
                }?.apply { isAccessible = true }
        }
        return invokeNoThrow(accessor, value) as? String
    }

    private fun cachedMethod(
        cache: ConcurrentHashMap<Class<*>, Method>,
        type: Class<*>,
        resolver: () -> Method?
    ): Method? {
        cache[type]?.let { return it }
        val resolved = resolver() ?: return null
        return cache.putIfAbsent(type, resolved) ?: resolved
    }

    private fun resolveItemModelAccessor(itemContractTypes: Collection<Class<*>>): Method? {
        return itemContractTypes
            .asSequence()
            .flatMap { type -> allInstanceMethods(type).asSequence() }
            .firstOrNull { candidate ->
                candidate.parameterCount == 0 &&
                    candidate.name != "clone" &&
                    candidate.name != "A02" &&
                    candidate.name != "BG7" &&
                    !candidate.returnType.isPrimitive &&
                    candidate.returnType != Any::class.java &&
                    candidate.returnType != String::class.java &&
                    !candidate.returnType.isEnum
            }?.apply { isAccessible = true }
    }

    private fun resolveItemEdgeAccessor(itemContractTypes: Collection<Class<*>>): Method? {
        return itemContractTypes
            .asSequence()
            .flatMap { type -> allInstanceMethods(type).asSequence() }
            .firstOrNull { candidate ->
                candidate.parameterCount == 0 &&
                    candidate.name != "clone" &&
                    (candidate.returnType == Any::class.java || candidate.returnType.name == GRAPHQL_FEED_UNIT_EDGE_CLASS)
            }?.apply { isAccessible = true }
    }

    private fun resolveItemNetworkAccessor(itemContractTypes: Collection<Class<*>>): Method? {
        return itemContractTypes
            .asSequence()
            .flatMap { type -> allInstanceMethods(type).asSequence() }
            .firstOrNull { candidate ->
                candidate.parameterCount == 0 &&
                    candidate.returnType == Boolean::class.javaPrimitiveType
            }?.apply { isAccessible = true }
    }

    private fun resolveChildAccessor(target: Any, acceptsValue: (Any?) -> Boolean): Method? {
        return allInstanceMethods(target.javaClass)
            .asSequence()
            .filter { candidate ->
                candidate.parameterCount == 0 &&
                    !candidate.returnType.isPrimitive &&
                    candidate.returnType != Void.TYPE &&
                    candidate.returnType != String::class.java &&
                    !candidate.returnType.isEnum &&
                    candidate.declaringClass != Any::class.java
            }
            .sortedByDescending { candidate -> scoreChildAccessor(candidate.returnType) }
            .firstOrNull { candidate ->
                acceptsValue(invokeNoThrow(candidate.apply { isAccessible = true }, target))
            }
    }

    private fun scoreChildAccessor(type: Class<*>): Int {
        return when {
            type.name == GRAPHQL_FEED_UNIT_EDGE_CLASS -> 4
            type.name.startsWith("com.facebook.graphql.model.") -> 3
            type.name.startsWith("com.facebook.") -> 2
            !type.name.startsWith("java.") &&
                !type.name.startsWith("javax.") &&
                !type.name.startsWith("android.") &&
                !type.name.startsWith("kotlin.") -> 1
            else -> 0
        }
    }

    private fun isSponsoredFeedCategory(value: String?): Boolean {
        return value != null && value in FEED_AD_CATEGORY_VALUES
    }

    private fun isSafeFeedContainerCategory(value: String?): Boolean {
        return value != null && value in FEED_SAFE_CONTAINER_CATEGORY_VALUES
    }

    private fun isLikelyAdTypeName(value: String?): Boolean {
        if (value == null) return false
        if (value.contains("QuickPromotion", ignoreCase = true)) return true
        return isAdSignalText(value)
    }

    private fun containsKnownAdSignals(value: Any?): Boolean {
        if (value == null) return false

        if (value is CharSequence) {
            return isAdSignalText(value.toString())
        }

        val type = value.javaClass
        if (isAdSignalText(type.name)) return true

        if (type.isEnum) {
            return isAdSignalText(value.toString())
        }

        if (type.isPrimitive || value is Number || value is Boolean) {
            return false
        }

        if (isAdSignalText(runCatching { value.toString() }.getOrNull())) return true

        for (method in stringAccessorsFor(type)) {
            val marker = invokeNoThrow(method, value) as? String
            if (isAdSignalText(marker)) return true
        }

        for (field in stringFieldsFor(type)) {
            val marker = runCatching { field.get(value) as? String }.getOrNull()
            if (isAdSignalText(marker)) return true
        }

        return false
    }

    private fun stringAccessorsFor(type: Class<*>): List<Method> {
        return stringAccessorCache.getOrPut(type) {
            allInstanceMethods(type)
                .asSequence()
                .filter { method ->
                    method.parameterCount == 0 &&
                        method.returnType == String::class.java &&
                        method.declaringClass != Any::class.java &&
                        method.name != "toString"
                }
                .take(12)
                .onEach { method -> method.isAccessible = true }
                .toList()
        }
    }

    private fun stringFieldsFor(type: Class<*>): List<Field> {
        return stringFieldCache.getOrPut(type) {
            val fields = ArrayList<Field>()
            var current: Class<*>? = type
            while (current != null && current != Any::class.java && fields.size < 12) {
                current.declaredFields.forEach { field ->
                    if (!Modifier.isStatic(field.modifiers) && field.type == String::class.java && fields.size < 12) {
                        field.isAccessible = true
                        fields.add(field)
                    }
                }
                current = current.superclass
            }
            fields
        }
    }

    private fun isAdSignalText(value: String?): Boolean {
        if (value.isNullOrBlank()) return false
        val normalized = value.lowercase()
        return FEED_AD_SIGNAL_TOKENS.any { token -> normalized.contains(token) }
    }

    private fun allInstanceMethods(type: Class<*>): List<Method> {
        return instanceMethodCache.getOrPut(type) {
            val methods = LinkedHashMap<String, Method>()
            var current: Class<*>? = type
            while (current != null && current != Any::class.java) {
                current.declaredMethods.forEach { method ->
                    if (!Modifier.isStatic(method.modifiers)) {
                        method.isAccessible = true
                        methods.putIfAbsent("${method.name}/${method.parameterCount}", method)
                    }
                }
                current.interfaces.forEach { iface ->
                    iface.declaredMethods.forEach { method ->
                        if (!Modifier.isStatic(method.modifiers)) {
                            method.isAccessible = true
                            methods.putIfAbsent("${method.name}/${method.parameterCount}", method)
                        }
                    }
                }
                current = current.superclass
            }
            methods.values.toList()
        }
    }

    private fun invokeNoThrow(method: Method?, target: Any?): Any? {
        if (method == null || target == null) return null
        return runCatching { method.invoke(target) }.getOrNull()
    }
}

internal fun logFacebook571SurvivingFeedTypeContracts(classLoader: ClassLoader) {
    if (!BuildConfig.DEBUG || survivingFeedTypeContractsLogged.getAndIncrement() != 0) return

    FB571_SURVIVING_FEED_TYPE_CLASSES.forEach { className ->
        logSurvivingFeedTypeContract(classLoader, className)
    }
}

internal fun logSurvivingFeedTypeContract(classLoader: ClassLoader, className: String) {
    val type = runCatching {
        Class.forName(className, false, classLoader)
    }.getOrElse {
        Logger.w(TAG, "SurvivingFeedType class unavailable=$className")
        return
    }
    Logger.i(
        TAG,
        "SurvivingFeedType class=${type.name} super=${type.superclass?.name} " +
            "interfaces=${type.interfaces.joinToString { it.name }}"
    )
    type.declaredFields.forEach { field ->
        Logger.i(
            TAG,
            "SurvivingFeedType field=${type.name}.${field.name}:${field.type.name} " +
                "static=${Modifier.isStatic(field.modifiers)}"
        )
    }
    type.declaredConstructors.forEach { constructor ->
        Logger.i(
            TAG,
            "SurvivingFeedType ctor=${type.name}(" +
                constructor.parameterTypes.joinToString { it.name } + ")"
        )
    }
    type.declaredMethods.forEach { method ->
        Logger.i(
            TAG,
            "SurvivingFeedType method=${type.name}.${method.name}(" +
                method.parameterTypes.joinToString { it.name } + "):${method.returnType.name} " +
                "static=${Modifier.isStatic(method.modifiers)}"
        )
    }
}

internal fun logFeedItems(source: String, items: Iterable<*>, feedItemInspector: FeedItemInspector) {
    var index = 0
    for (item in items) {
        Logger.i(TAG, "FeedItem $source[$index] ${feedItemInspector.describe(item)}")
        index++
    }
    Logger.i(TAG, "FeedItem $source count=$index")
}

internal fun hookStoryAdsMerge(method: Method, source: String) {
    XposedBridge.hookMethod(method, object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val originalBuckets = param.args.getOrNull(2)
            if (originalBuckets != null) {
                param.result = originalBuckets
                Logger.i(TAG, "Blocked story ad bucket merge in $source")
            }
        }
    })
}

internal fun hookStoryAdsNoOp(method: Method, reason: String, source: String) {
    XposedBridge.hookMethod(method, object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            param.result = null
            Logger.i(TAG, "Blocked $reason in $source")
        }
    })
}

internal fun hookStoryAdProvider(provider: StoryAdProviderHooks) {
    if (!storyAdProviderClassesHooked.add(provider.providerClass.name)) return

    val hooked = ArrayList<String>()

    provider.mergeMethod?.let { method ->
        hookStoryAdsMerge(method, provider.providerClass.name)
        hooked.add("merge")
    }
    provider.fetchMoreAdsMethod?.let { method ->
        hookStoryAdsNoOp(method, "story ad fetchMoreAds", provider.providerClass.name)
        hooked.add("fetchMoreAds")
    }
    provider.deferredUpdateMethod?.let { method ->
        hookStoryAdsNoOp(method, "story ad deferred update", provider.providerClass.name)
        hooked.add("deferredUpdate")
    }
    provider.insertionTriggerMethod?.let { method ->
        hookStoryAdsNoOp(method, "story ad insertion trigger", provider.providerClass.name)
        hooked.add("insertionTrigger")
    }

    if (hooked.isNotEmpty()) {
        Logger.i(TAG, "Hooked story ad provider ${provider.providerClass.name}: ${hooked.joinToString()}")
    }
}

internal fun hookSponsoredPoolListMethods(poolClass: Class<*>) {
    var hooked = 0
    poolClass.declaredMethods
        .filter { method ->
            !Modifier.isStatic(method.modifiers) &&
                method.parameterCount == 0 &&
                List::class.java.isAssignableFrom(method.returnType)
        }
        .forEach { method ->
            method.isAccessible = true
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.result = arrayListOf<Any?>()
                }
            })
            hooked++
        }
    Logger.i(TAG, "Hooked $hooked feed pool list method(s) on ${poolClass.name}")
}

internal fun hookSponsoredPoolResultMethods(poolClass: Class<*>) {
    var hooked = 0
    poolClass.declaredMethods
        .filter { method ->
            !Modifier.isStatic(method.modifiers) &&
                isSponsoredResultCarrier(method.returnType) &&
                (
                    method.parameterCount == 0 ||
                        (method.parameterCount == 1 && method.parameterTypes[0] == Boolean::class.javaPrimitiveType)
                    )
        }
        .forEach { method ->
            method.isAccessible = true
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    buildSponsoredEmptyResult(method.returnType)?.let { emptyResult ->
                        param.result = emptyResult
                    }
                }
            })
            hooked++
        }
    Logger.i(TAG, "Hooked $hooked feed pool result method(s) on ${poolClass.name}")
}

internal fun isSponsoredResultCarrier(type: Class<*>): Boolean {
    val constructor = type.declaredConstructors.firstOrNull { it.parameterCount == 2 } ?: return false
    val reasonType = constructor.parameterTypes.getOrNull(1) ?: return false
    return reasonType.enumConstants?.any { it.toString() == "SPONSORED_GET_NEXT_RETURN_NULL" } == true
}

internal fun buildSponsoredEmptyResult(type: Class<*>): Any? {
    val constructor = type.declaredConstructors.firstOrNull { it.parameterCount == 2 } ?: return null
    val reasonType = constructor.parameterTypes.getOrNull(1) ?: return null
    val emptyReason = reasonType.enumConstants?.firstOrNull { it.toString() == "SPONSORED_GET_NEXT_RETURN_NULL" }
        ?: reasonType.enumConstants?.firstOrNull { it.toString() == "FAIL" }
        ?: return null
    constructor.isAccessible = true
    return constructor.newInstance(null, emptyReason)
}

internal fun filterAdItems(list: MutableList<Any?>, inspector: AdStoryInspector): Int {
    var removed = 0
    val iterator = list.iterator()
    while (iterator.hasNext()) {
        if (inspector.containsAdStory(iterator.next())) {
            iterator.remove()
            removed++
        }
    }
    return removed
}

internal fun buildImmutableListLike(sample: Any?, items: List<Any?>): Any? {
    if (sample == null) return null
    return runCatching {
        val immutableListClass = Class.forName(
            "com.google.common.collect.ImmutableList",
            false,
            sample.javaClass.classLoader
        )
        val copyOf = immutableListClass.getDeclaredMethod("copyOf", Iterable::class.java)
        copyOf.invoke(null, items)
    }.getOrNull()
}

internal fun replaceFeedItemsInResult(param: XC_MethodHook.MethodHookParam, items: List<Any?>): Boolean {
    val result = param.result ?: return false
    val rebuiltResult = rebuildFeedResult(result, items) ?: return false
    param.result = rebuiltResult
    return true
}

internal fun rebuildFeedResult(result: Any, items: List<Any?>): Any? {
    val type = result.javaClass
    val fields = runCatching {
        type.declaredFields.onEach { it.isAccessible = true }
    }.getOrNull() ?: return null

    val listField = fields.firstOrNull { candidate ->
        !Modifier.isStatic(candidate.modifiers) &&
            Iterable::class.java.isAssignableFrom(candidate.type)
    } ?: return null

    val intArrayField = fields.firstOrNull { candidate ->
        !Modifier.isStatic(candidate.modifiers) && candidate.type == IntArray::class.java
    } ?: return null

    val intFields = fields.filter { candidate ->
        !Modifier.isStatic(candidate.modifiers) && candidate.type == Int::class.javaPrimitiveType
    }
    if (intFields.size < 3) return null

    val originalList = runCatching { listField.get(result) }.getOrNull()
    val rebuiltList = buildImmutableListLike(originalList, items) ?: return null
    val stats = runCatching { intArrayField.get(result) as? IntArray }.getOrNull()?.clone() ?: return null
    val ints = intFields.map { field -> runCatching { field.getInt(result) }.getOrNull() ?: return null }

    val constructor = type.declaredConstructors.firstOrNull { constructor ->
        constructor.parameterCount == 5 &&
            constructor.parameterTypes.getOrNull(0)?.name == "com.google.common.collect.ImmutableList" &&
            constructor.parameterTypes.getOrNull(1) == IntArray::class.java &&
            constructor.parameterTypes.drop(2).all { it == Int::class.javaPrimitiveType }
    } ?: return null

    constructor.isAccessible = true
    return runCatching {
        constructor.newInstance(rebuiltList, stats, ints[0], ints[1], ints[2])
    }.getOrNull()
}

internal fun extractFeedItemsFromResult(result: Any?): Iterable<*>? {
    if (result == null) return null
    if (result is Iterable<*>) return result

    return runCatching {
        val field = result.javaClass.declaredFields.firstOrNull { candidate ->
            Iterable::class.java.isAssignableFrom(candidate.type)
        } ?: return null
        field.isAccessible = true
        field.get(result) as? Iterable<*>
    }.getOrNull()
}

internal fun hookListResultFilter(method: Method, source: String, inspector: AdStoryInspector) {
    XposedBridge.hookMethod(method, object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            val result = param.result as? MutableList<Any?> ?: return
            val removed = filterAdItems(result, inspector)
            if (removed > 0) {
                Logger.i(TAG, "Removed $removed ad item(s) from $source")
            }
        }
    })
}

internal fun hookFeedCsrFilterInput(
    hook: FeedCsrFilterHook,
    feedItemInspector: FeedItemInspector
): Boolean {
    if (!feedCsrMethodsHooked.add(methodHookKey(hook.method))) {
        return false
    }
    XposedBridge.hookMethod(hook.method, object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val filterName = hook.method.declaringClass.name
            val originalList = param.args.getOrNull(hook.listArgIndex) as? Iterable<*>
            if (originalList == null) return
            logFeedItems("$filterName IN", originalList, feedItemInspector)
            val keptItems = ArrayList<Any?>()
            var removed = 0

            for (item in originalList) {
                if (feedItemInspector.isDefinitelySponsoredFeedItem(item)) {
                    removed++
                } else {
                    keptItems.add(item)
                }
            }

            if (removed <= 0) return

            val rebuilt = buildImmutableListLike(param.args.getOrNull(hook.listArgIndex), keptItems) ?: return
            param.args[hook.listArgIndex] = rebuilt
            Logger.i(TAG, "Removed $removed sponsored feed item(s) before ${hook.method.declaringClass.name}.${hook.method.name}")
        }

        override fun afterHookedMethod(param: MethodHookParam) {
            val filterName = hook.method.declaringClass.name
            val resultItems = extractFeedItemsFromResult(param.result)
            if (resultItems != null) {
                logFeedItems("$filterName OUT", resultItems, feedItemInspector)
                val keptItems = ArrayList<Any?>()
                var removed = 0
                for (item in resultItems) {
                    if (feedItemInspector.isDefinitelySponsoredFeedItem(item)) {
                        removed++
                    } else {
                        keptItems.add(item)
                    }
                }
                if (removed > 0 && replaceFeedItemsInResult(param, keptItems)) {
                    Logger.i(TAG, "Removed $removed sponsored feed item(s) from result of ${hook.method.declaringClass.name}.${hook.method.name}")
                }
            }
        }
    })
    return true
}

internal fun hookLateFeedListSanitizer(
    hook: FeedListSanitizerHook,
    feedItemInspector: FeedItemInspector
): Boolean {
    if (!lateFeedMethodsHooked.add(methodHookKey(hook.method))) {
        return false
    }
    XposedBridge.hookMethod(hook.method, object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val originalList = param.args.getOrNull(hook.listArgIndex) as? Iterable<*> ?: return
            val keptItems = ArrayList<Any?>()
            var removed = 0

            for (item in originalList) {
                if (feedItemInspector.isDefinitelySponsoredFeedItem(item)) {
                    removed++
                } else {
                    keptItems.add(item)
                }
            }

            if (removed <= 0) return

            val rebuilt = buildImmutableListLike(param.args.getOrNull(hook.listArgIndex), keptItems) ?: return
            param.args[hook.listArgIndex] = rebuilt
            Logger.i(
                TAG,
                "Late-stage removed $removed sponsored feed item(s) before ${hook.method.declaringClass.name}.${hook.method.name}"
            )
        }
    })
    return true
}

internal fun hookStoryPoolAdd(method: Method, feedItemInspector: FeedItemInspector) {
    XposedBridge.hookMethod(method, object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val item = param.args.getOrNull(0)
            val blockReason = feedItemInspector.storyPoolBlockReason(item)
            if (blockReason == null) {
                if (feedItemInspector.isSponsoredFeedItem(item)) {
                    logHookHitThrottled("storyPoolBroadAllowed", method, feedItemInspector.describe(item))
                }
                return
            }

            param.result = false
            logHookHitThrottled(
                if (blockReason == "strict") "storyPoolStrictBlock" else "storyPoolBroadNetworkBlock",
                method,
                feedItemInspector.describe(item)
            )
        }
    })
}

internal fun hookSponsoredPoolAdd(method: Method): Boolean {
    if (!sponsoredPoolMethodsHooked.add(methodHookKey(method))) {
        return false
    }
    XposedBridge.hookMethod(method, object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            param.result = false
            logHookHitThrottled("sponsoredPoolBlock", method)
        }
    })
    return true
}

internal fun hookSponsoredStoryNext(method: Method) {
    XposedBridge.hookMethod(method, object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            param.result = null
            Logger.i(TAG, "Blocked sponsored story vending from feed manager")
        }
    })
}

internal fun hookSponsoredStoryListMethods(managerClass: Class<*>) {
    var hooked = 0
    managerClass.declaredMethods
        .filter { method ->
            !Modifier.isStatic(method.modifiers) &&
                isSponsoredStoryListMethod(method)
        }
        .forEach { method ->
            method.isAccessible = true
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    buildEmptyListReturn(method.returnType)?.let { emptyResult ->
                        param.result = emptyResult
                    }
                }
            })
            hooked++
        }
    Logger.i(TAG, "Hooked $hooked sponsored story list method(s) on ${managerClass.name}")
}

internal fun isSponsoredStoryListMethod(method: Method): Boolean {
    if (method.parameterCount > 2) return false
    if (!Iterable::class.java.isAssignableFrom(method.returnType) &&
        method.returnType.name != "com.google.common.collect.ImmutableList"
    ) {
        return false
    }
    return method.parameterTypes.all { type ->
        type == Int::class.javaPrimitiveType ||
            type == Long::class.javaPrimitiveType ||
            type == Boolean::class.javaPrimitiveType
    }
}

internal fun buildEmptyListReturn(returnType: Class<*>): Any? {
    if (returnType.name == "com.google.common.collect.ImmutableList") {
        return runCatching {
            val of = returnType.getDeclaredMethod("of")
            of.isAccessible = true
            of.invoke(null)
        }.getOrNull()
    }
    return when {
        returnType.isAssignableFrom(ArrayList::class.java) -> arrayListOf<Any?>()
        Iterable::class.java.isAssignableFrom(returnType) -> emptyList<Any?>()
        else -> null
    }
}
