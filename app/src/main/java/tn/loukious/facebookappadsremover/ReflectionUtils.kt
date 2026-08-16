package tn.loukious.facebookappadsremover

import android.view.View
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.ArrayDeque
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap

internal val hierarchyMethodCache = ConcurrentHashMap<Class<*>, List<Method>>()
internal val instanceMethodCache = ConcurrentHashMap<Class<*>, List<Method>>()
internal val hierarchyFieldCache = ConcurrentHashMap<Class<*>, List<Field>>()
internal val interfaceCache = ConcurrentHashMap<Class<*>, List<Class<*>>>()

internal fun Collection<MethodData>.firstMethodInstanceOrNull(classLoader: ClassLoader): Method? {
    return asSequence()
        .mapNotNull { methodData ->
            runCatching { methodData.getMethodInstance(classLoader) }.getOrNull()
        }
        .firstOrNull { method ->
            method.name != "<init>" && method.name != "<clinit>"
        }?.apply { isAccessible = true }
}

internal fun findClassesByZeroArgStringTags(
    bridge: DexKitBridge,
    tags: Collection<String>
): List<ClassData> {
    val candidates = LinkedHashMap<String, ClassData>()
    tags.forEach { tag ->
        bridge.findClass {
            matcher {
                methods {
                    matchType = org.luckypray.dexkit.query.enums.MatchType.Contains
                    add {
                        returnType = "java.lang.String"
                        paramCount = 0
                        usingStrings(tag)
                    }
                }
            }
        }.forEach { candidate ->
            candidates.putIfAbsent(candidate.name, candidate)
        }
    }
    return candidates.values.toList()
}

internal fun resolveAdKindEnumClass(
    classLoader: ClassLoader,
    batchCandidates: Collection<ClassData>,
    bridge: DexKitBridge
): Class<*>? {
    val directCandidates = if (batchCandidates.isNotEmpty()) {
        batchCandidates
    } else {
        bridge.findClass {
            matcher {
                usingEqStrings("AD", "UGC", "PARADE", "MIDCARD")
            }
        }
    }

    directCandidates.forEach { candidate ->
        val clazz = runCatching { candidate.getInstance(classLoader) }.getOrNull() ?: return@forEach
        val constants = clazz.enumConstants?.map { it.toString() }.orEmpty()
        if (clazz.isEnum && "AD" in constants && "UGC" in constants) {
            return clazz
        }
    }

    return null
}

internal fun invokeMethodByName(target: Any?, methodName: String, vararg args: Any?): Any? {
    if (target == null) return null
    val method = allMethodsInHierarchy(target.javaClass).firstOrNull { candidate ->
        candidate.name == methodName &&
            candidate.parameterCount == args.size &&
            candidate.parameterTypes.zip(args).all { (parameterType, argument) ->
                argument == null || parameterType.isAssignableFrom(argument.javaClass)
            }
    } ?: return null
    method.isAccessible = true
    return runCatching { method.invoke(target, *args) }.getOrNull()
}

internal fun allInterfacesInHierarchy(type: Class<*>): List<Class<*>> {
    if (interfaceCache.size > 1000) interfaceCache.clear()
    return interfaceCache.getOrPut(type) {
        val result = LinkedHashMap<String, Class<*>>()
        val queue = ArrayDeque<Class<*>>()
        queue.add(type)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            current.interfaces.forEach { iface ->
                if (result.putIfAbsent(iface.name, iface) == null) {
                    queue.add(iface)
                }
            }
            current.superclass?.let(queue::add)
        }
        result.values.toList()
    }
}

internal fun allFieldsInHierarchy(type: Class<*>): List<Field> {
    if (hierarchyFieldCache.size > 1000) hierarchyFieldCache.clear()
    return hierarchyFieldCache.getOrPut(type) {
        val fields = ArrayList<Field>()
        var current: Class<*>? = type
        while (current != null && current != Any::class.java && isNonStandardClass(current) && fields.size < 200) {
            fields.addAll(current.declaredFields)
            current = current.superclass
        }
        fields
    }
}

internal fun allMethodsInHierarchy(type: Class<*>): List<Method> {
    if (hierarchyMethodCache.size > 1000) hierarchyMethodCache.clear()
    return hierarchyMethodCache.getOrPut(type) {
        val methods = LinkedHashMap<String, Method>()
        var current: Class<*>? = type
        while (current != null && current != Any::class.java && isNonStandardClass(current)) {
            current.declaredMethods.forEach { method ->
                methods.putIfAbsent(
                    "${method.name}:${method.parameterTypes.joinToString { it.name }}",
                    method
                )
            }
            current = current.superclass
        }
        methods.values.toList()
    }
}

internal fun methodHookKey(method: Method): String {
    return "${method.declaringClass.name}#${method.name}(" +
        method.parameterTypes.joinToString(",") { it.name } +
        "):${method.returnType.name}"
}

internal fun findViewOnClickListener(view: View): Any? {
    return findViewListenerInfoField(view, "mOnClickListener")
}

internal fun findViewOnTouchListener(view: View): Any? {
    return findViewListenerInfoField(view, "mOnTouchListener")
}

internal fun findViewListenerInfoField(view: View, fieldName: String): Any? {
    return runCatching {
        val listenerInfoField = View::class.java.getDeclaredField("mListenerInfo").apply {
            isAccessible = true
        }
        val listenerInfo = listenerInfoField.get(view) ?: return@runCatching null
        val listenerField = listenerInfo.javaClass.getDeclaredField(fieldName).apply {
            isAccessible = true
        }
        listenerField.get(listenerInfo)
    }.getOrNull()
}

internal fun isFeedListType(type: Class<*>): Boolean {
    return Iterable::class.java.isAssignableFrom(type) ||
        type.name == "com.google.common.collect.ImmutableList"
}

internal fun methodSignature(method: Method): String {
    return "${method.declaringClass.name}.${method.name}(${method.parameterTypes.joinToString(",") { it.name }}):${method.returnType.name}"
}

internal fun isNonStandardClass(type: Class<*>): Boolean {
    val name = type.name
    return !name.startsWith("java.") &&
        !name.startsWith("android.") &&
        !name.startsWith("kotlin.") &&
        !name.startsWith("androidx.") &&
        !name.startsWith("com.android.") &&
        !name.startsWith("javax.") &&
        !name.startsWith("dalvik.") &&
        !name.startsWith("libcore.")
}

internal fun messagePeekData(message: android.os.Message): android.os.Bundle? {
    return runCatching {
        val peekDataMethod = android.os.Message::class.java.getDeclaredMethod("peekData").apply {
            isAccessible = true
        }
        peekDataMethod.invoke(message) as? android.os.Bundle
    }.getOrNull()
}
