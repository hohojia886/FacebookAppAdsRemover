package tn.loukious.facebookappadsremover

import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.IdentityHashMap
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap

class AdStoryInspector(
    private val adKindEnumClass: Class<*>
) {
    private val enumMethodCache = ConcurrentHashMap<Class<*>, List<Method>>()
    private val fieldCache = ConcurrentHashMap<Class<*>, List<Field>>()

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
        return methods.values.toList()
    }

    private fun isReelsAdSignalText(value: String?): Boolean {
        if (value.isNullOrBlank()) return false
        val normalized = value.lowercase()
        return REELS_AD_SIGNAL_TOKENS.any { token -> normalized.contains(token) }
    }
}

class FeedItemInspector(
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

        val model = invokeNoThrow(itemModelAccessor, item)
        val edge = edgeFrom(item)
        val feedUnit = feedUnitFrom(edge)
        val backendData = backendDataFrom(edge)
        
        val modelCategory = readCategory(model) ?: "unknown"
        val edgeCategory = readEdgeCategory(edge) ?: readCategory(edge) ?: "unknown"
        val network = (invokeNoThrow(itemNetworkAccessor, item) as? Boolean)?.toString() ?: "unknown"
        val inflatedUnitClass = feedUnit?.javaClass?.name ?: "null"
        val inflatedTypeName = readTypeName(feedUnit) ?: "unknown"
        val backendUnitClass = backendData?.javaClass?.name ?: "null"
        val backendTypeName = readTypeName(backendData) ?: "unknown"

        return "modelCat=$modelCategory edgeCat=$edgeCategory isAd=${isSponsoredFeedItem(item)} network=$network wrapper=${item.javaClass.name} inflated=$inflatedUnitClass/$inflatedTypeName backend=$backendUnitClass/$backendTypeName"
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
        val methods = LinkedHashMap<String, Method>()
        var current: Class<*>? = type
        while (current != null && current != Any::class.java) {
            current.declaredMethods.forEach { method ->
                if (!Modifier.isStatic(method.modifiers)) {
                    method.isAccessible = true
                    methods.putIfAbsent("${current.name}#${method.name}/${method.parameterCount}", method)
                }
            }
            current.interfaces.forEach { iface ->
                iface.declaredMethods.forEach { method ->
                    if (!Modifier.isStatic(method.modifiers)) {
                        method.isAccessible = true
                        methods.putIfAbsent("${iface.name}#${method.name}/${method.parameterCount}", method)
                    }
                }
            }
            current = current.superclass
        }
        return methods.values.toList()
    }

    private fun invokeNoThrow(method: Method?, target: Any?): Any? {
        if (method == null || target == null) return null
        return runCatching { method.invoke(target) }.getOrNull()
    }
}
