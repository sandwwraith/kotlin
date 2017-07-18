/*
 * Copyright 2010-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package kotlin.serialization.internal

import kotlin.reflect.KClass
import kotlin.reflect.full.companionObjectInstance
import kotlin.reflect.full.isSubclassOf
import kotlin.serialization.*

/**
 *  @author Leonid Startsev
 *          sandwwraith@gmail.com
 */

object PolymorphicClassDesc : SerialClassDescImpl("kotlin.Any") {
    override val kind: KSerialClassKind = KSerialClassKind.POLYMORPHIC

    init {
        addElement("klass")
        addElement("value")
    }
}

object RealDynamicSerializer : KSerializer<Any> {

    override val serialClassDesc: KSerialClassDesc
        get() = PolymorphicClassDesc

    override fun save(output: KOutput, obj: Any) {
        val saver = resolveSaver(obj)
        val output = output.writeBegin(serialClassDesc)
        output.writeStringElementValue(serialClassDesc, 0, saver.serialClassDesc.name)
        output.writeSerializableElementValue(serialClassDesc, 1, saver, obj)
        output.writeEnd(serialClassDesc)
    }

    override fun load(input: KInput): Any {
        val input = input.readBegin(serialClassDesc)
        var klassName: String? = null
        var value: Any? = null
        mainLoop@ while (true) {
            when (input.readElement(serialClassDesc)) {
                KInput.READ_ALL -> {
                    klassName = input.readStringElementValue(serialClassDesc, 0)
                    val loader = resolveLoader<Any>(klassName)
                    value = input.readSerializableElementValue(serialClassDesc, 1, loader)
                    break@mainLoop
                }
                KInput.READ_DONE -> {
                    break@mainLoop
                }
                0 -> {
                    klassName = input.readStringElementValue(serialClassDesc, 0)
                }
                1 -> {
                    klassName = requireNotNull(klassName) { "Cannot read polymorphic value before its type token" }
                    val loader = resolveLoader<Any>(klassName)
                    value = input.readSerializableElementValue(serialClassDesc, 1, loader)
                }
                else -> throw SerializationException("Invalid index")
            }
        }

        input.readEnd(serialClassDesc)
        return requireNotNull(value) { "Polymorphic value have not been read" }
    }
}


// can change later
internal typealias DynamicSerializer = RealDynamicSerializer

//todo : Short::class and others
//todo : Hardcoded class names for kotlin.collections... because they cannot be found by Class.forName
internal object SerialCache {


    val map: Map<KClass<*>, KSerializer<*>> = mapOf(
            Unit::class to UnitSerializer,
            Boolean::class to BooleanSerializer,
            Int::class to IntSerializer,
            Long::class to LongSerializer,
            Double::class to DoubleSerializer,
            Char::class to CharSerializer,
            String::class to StringSerializer,
            Collection::class to ArrayListSerializer(makeNullable(DynamicSerializer)),
            List::class to ArrayListSerializer(makeNullable(DynamicSerializer)),
            HashSet::class to HashSetSerializer(makeNullable(DynamicSerializer)),
            Set::class to LinkedHashSetSerializer(makeNullable(DynamicSerializer)),
            HashMap::class to HashMapSerializer(makeNullable(DynamicSerializer), makeNullable(DynamicSerializer)),
            Map::class to LinkedHashMapSerializer(makeNullable(DynamicSerializer), makeNullable(DynamicSerializer)),
            Map.Entry::class to MapEntrySerializer(makeNullable(DynamicSerializer), makeNullable(DynamicSerializer))
    )

    @Suppress("UNCHECKED_CAST")
    fun <E> getBuiltInSerializer(klass: KClass<*>): KSerializer<E>? {
        for ((k, v) in map) {
            if (klass.isSubclassOf(k)) return v as KSerializer<E>
        }
        return null
    }

    @Suppress("UNCHECKED_CAST")
    fun <E> lookupSerializer(klass: KClass<*>): KSerializer<E> {

        var saver = klass.let { SerialCache.getBuiltInSerializer<E>(it) }

        if (saver == null) {
            //check for built-in types
            saver = (klass.companionObjectInstance) as? KSerializer<E>
        }
        return requireNotNull(saver) { "Can't found internal serializer for class $klass" }
    }
}

fun <E> resolveSaver(value: E): KSerializer<E> {
    val klass = (value as? Any)?.javaClass?.kotlin ?: throw SerializationException("Cannot determine class for value $value")
    return SerialCache.lookupSerializer(klass)
}

fun <E> resolveLoader(className: String): KSerializer<E> {
    val klass = Class.forName(className).kotlin
    return SerialCache.lookupSerializer(klass)
}