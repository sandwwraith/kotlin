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

package org.jetbrains.kotlinx.serialization.compiler.resolve

import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.PropertyDescriptor
import org.jetbrains.kotlin.descriptors.ValueParameterDescriptor
import org.jetbrains.kotlin.descriptors.annotations.Annotations
import org.jetbrains.kotlin.descriptors.findClassAcrossModuleDependencies
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.resolve.descriptorUtil.module
import org.jetbrains.kotlin.types.*

internal val packageFqName = FqName("kotlinx.serialization")

// ---- kotlin.serialization.KSerializer

internal val kSerializerName = Name.identifier("KSerializer")
internal val kSerializerConstructorMarkerName = Name.identifier("SerializationConstructorMarker")
internal val kSerializerFqName = packageFqName.child(kSerializerName)

fun isKSerializer(type: KotlinType?): Boolean =
        type != null && KotlinBuiltIns.isConstructedFromGivenClass(type, kSerializerFqName)

fun ClassDescriptor.getKSerializerDescriptor(): ClassDescriptor =
        module.findClassAcrossModuleDependencies(ClassId(packageFqName, kSerializerName))!!


fun ClassDescriptor.getKSerializerType(argument: SimpleType): SimpleType {
    val projectionType = Variance.INVARIANT
    val types = listOf(TypeProjectionImpl(projectionType, argument))
    return KotlinTypeFactory.simpleNotNullType(Annotations.EMPTY, getKSerializerDescriptor(), types)
}

// ---- java.io.Serializable

internal val javaIOPackageFqName = FqName("java.io")
internal val javaSerializableName = Name.identifier("Serializable")
internal val javaSerializableFqName = javaIOPackageFqName.child(javaSerializableName)

fun isJavaSerializable(type: KotlinType?): Boolean =
        type != null && KotlinBuiltIns.isConstructedFromGivenClass(type, javaSerializableFqName)

fun ClassDescriptor.getJavaSerializableDescriptor(): ClassDescriptor =
        module.findClassAcrossModuleDependencies(ClassId(javaIOPackageFqName, javaSerializableName))!!

fun ClassDescriptor.getJavaSerializableType(): SimpleType {
    return KotlinTypeFactory.simpleNotNullType(Annotations.EMPTY, getJavaSerializableDescriptor(), emptyList())
}

// ---- kotlin.serialization.Serializable(with=xxx)

internal val serializableAnnotationFqName = FqName("kotlinx.serialization.Serializable")

internal val Annotations.serializableWith: KotlinType?
    get() = findAnnotationValue(serializableAnnotationFqName, "with")

// ---- kotlin.serialization.Serializer(forClass=xxx)

internal val serializerAnnotationFqName = FqName("kotlinx.serialization.Serializer")

internal val Annotations.serializerForClass: KotlinType?
    get() = findAnnotationValue(serializerAnnotationFqName, "forClass")

// ---- kotlin.serialization.SerialName(value=xxx)

internal val serialNameAnnotationFqName = FqName("kotlinx.serialization.SerialName")

val Annotations.serialNameValue: String?
    get() {
        val value = findAnnotationValue<String?>(serialNameAnnotationFqName, "value")
        return value
    }

// ---- kotlin.serialization.Optional

internal val serialOptionalFqName = FqName("kotlinx.serialization.Optional")

val Annotations.serialOptional: Boolean
    get() = hasAnnotation(serialOptionalFqName)

// ---- kotlin.serialization.Transient

internal val serialTransientFqName = FqName("kotlinx.serialization.Transient")

val Annotations.serialTransient: Boolean
    get() = hasAnnotation(serialTransientFqName)

internal val serialInfoFqName = FqName("kotlinx.serialization.SerialInfo")

// ----------------------------------------

val KotlinType?.toClassDescriptor: ClassDescriptor?
    @JvmName("toClassDescriptor")
    get() = this?.constructor?.declarationDescriptor as? ClassDescriptor


val ClassDescriptor.isInternalSerializable: Boolean //todo normal checking
    get() = annotations.hasAnnotation(serializableAnnotationFqName) && annotations.serializableWith == null

// serializer that was declared for this type
internal val ClassDescriptor?.classSerializer: KotlinType?
    get() = this?.let {
        // serializer annotation on class?
        annotations.serializableWith?.let { return it }
        // default serializable?
        if (isInternalSerializable) return companionObjectDescriptor?.defaultType
        return null
    }

// serializer that was declared for this specific type or annotation from a class declaration
val KotlinType?.typeSerializer: KotlinType?
    get() = this?.let {
        // serializer annotation on this type or from a class
        return it.annotations.serializableWith ?: (it.toClassDescriptor).classSerializer
    }

// serializer that was declared specifically for this property via its own annotation or via annotation on its type
val PropertyDescriptor.propertySerializer: KotlinType?
    get() = annotations.serializableWith ?: type.typeSerializer


fun getSerializableClassDescriptorBySerializer(serializerDescriptor: ClassDescriptor): ClassDescriptor? {
    val serializerForClass = serializerDescriptor.annotations.serializerForClass
    if (serializerForClass != null) return serializerForClass.toClassDescriptor
    if (!serializerDescriptor.isCompanionObject) return null
    val classDescriptor = (serializerDescriptor.containingDeclaration as? ClassDescriptor) ?: return null
    if (!classDescriptor.isInternalSerializable) return null
    return classDescriptor
}

// todo: serialization: do an actual check
fun ClassDescriptor.checkSerializableClassPropertyResult(type: KotlinType): Boolean = true

// todo: serialization: do an actual check better that just number of parameters
fun ClassDescriptor.checkSaveMethodParameters(parameters: List<ValueParameterDescriptor>): Boolean =
        parameters.size == 2

fun ClassDescriptor.checkSaveMethodResult(type: KotlinType): Boolean =
        KotlinBuiltIns.isUnit(type)

// todo: serialization: do an actual check better that just number of parameters
fun ClassDescriptor.checkLoadMethodParameters(parameters: List<ValueParameterDescriptor>): Boolean =
        parameters.size == 1

fun ClassDescriptor.checkLoadMethodResult(type: KotlinType): Boolean = getSerializableClassDescriptorBySerializer(this)?.defaultType == type

// ----------------

inline fun <reified R> Annotations.findAnnotationValue(annotationFqName: FqName, property: String): R? =
        findAnnotation(annotationFqName)?.let { annotation ->
            annotation.allValueArguments.entries.singleOrNull { it.key.asString() == property }?.value?.value
        } as? R

// Search utils

fun ClassDescriptor.getKSerializerConstructorMarker(): ClassDescriptor =
        module.findClassAcrossModuleDependencies(ClassId(packageFqName, kSerializerConstructorMarkerName))!!

fun ClassDescriptor.getClassFromSerializationPackage(classSimpleName: String) =
        module.findClassAcrossModuleDependencies(ClassId(packageFqName, Name.identifier(classSimpleName)))!!

fun ClassDescriptor.toSimpleType(nullable: Boolean = true) = KotlinTypeFactory.simpleType(Annotations.EMPTY, this.typeConstructor, emptyList(), nullable)
