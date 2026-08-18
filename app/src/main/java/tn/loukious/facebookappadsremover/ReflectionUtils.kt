package tn.loukious.facebookappadsremover

import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.ArrayDeque
import java.util.LinkedHashMap

fun Iterable<MethodData>.firstMethodInstanceOrNull(classLoader: ClassLoader): Method? {
    return asSequence()
        .mapNotNull { methodData ->
            runCatching { methodData.getMethodInstance(classLoader) }.getOrNull()
        }
        .firstOrNull { method ->
            method.name != "<init>" && method.name != "<clinit>"
        }?.apply { isAccessible = true }
}

fun allInterfacesInHierarchy(type: Class<*>): List<Class<*>> {
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
    return result.values.toList()
}

fun allFieldsInHierarchy(type: Class<*>): List<Field> {
    val fields = ArrayList<Field>()
    var current: Class<*>? = type
    while (current != null && current != Any::class.java && fields.size < 200) {
        fields.addAll(current.declaredFields)
        current = current.superclass
    }
    return fields
}

fun allMethodsInHierarchy(type: Class<*>): List<Method> {
    val methods = LinkedHashMap<String, Method>()
    var current: Class<*>? = type
    while (current != null && current != Any::class.java) {
        current.declaredMethods.forEach { method ->
            methods.putIfAbsent(
                "${method.name}:${method.parameterTypes.joinToString { it.name }}",
                method
            )
        }
        current = current.superclass
    }
    return methods.values.toList()
}

fun invokeMethodByName(target: Any?, methodName: String, vararg args: Any?): Any? {
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

// [2026-08-17 20:27] Project C: Re-implementing Type-check Short-circuiting utility
internal fun isNonTargetClass(clazz: Class<*>): Boolean {
    val name = clazz.name
    return name.startsWith("java.") || 
           name.startsWith("android.") || 
           name.startsWith("androidx.") || 
           name.startsWith("kotlin.") || 
           name.startsWith("kotlinx.") ||
           (name.startsWith("com.google.") && !name.contains("ImmutableList"))
}
