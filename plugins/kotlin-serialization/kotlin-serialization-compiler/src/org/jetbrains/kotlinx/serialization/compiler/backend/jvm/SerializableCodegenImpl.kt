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

package org.jetbrains.kotlinx.serialization.compiler.backend.jvm

import org.jetbrains.kotlin.codegen.CompilationException
import org.jetbrains.kotlin.codegen.ExpressionCodegen
import org.jetbrains.kotlin.codegen.ImplementationBodyCodegen
import org.jetbrains.kotlin.descriptors.ClassConstructorDescriptor
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.descriptors.PropertyDescriptor
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.resolve.descriptorUtil.getSuperClassOrAny
import org.jetbrains.kotlin.resolve.jvm.jvmSignature.JvmMethodSignature
import org.jetbrains.kotlinx.serialization.compiler.backend.common.SerializableCodegen
import org.jetbrains.kotlinx.serialization.compiler.backend.common.anonymousInitializers
import org.jetbrains.kotlinx.serialization.compiler.backend.common.bodyPropertiesDescriptorsMap
import org.jetbrains.kotlinx.serialization.compiler.backend.common.primaryPropertiesDescriptorsMap
import org.jetbrains.kotlinx.serialization.compiler.resolve.SerializableProperties
import org.jetbrains.kotlinx.serialization.compiler.resolve.SerializableProperty
import org.jetbrains.kotlinx.serialization.compiler.resolve.isInternalSerializable
import org.jetbrains.org.objectweb.asm.Label
import org.jetbrains.org.objectweb.asm.Type
import org.jetbrains.org.objectweb.asm.commons.InstructionAdapter

class SerializableCodegenImpl(
        private val classCodegen: ImplementationBodyCodegen,
        serializableClass: ClassDescriptor
) : SerializableCodegen(classCodegen.myClass, classCodegen.bindingContext) {

    private val thisAsmType = classCodegen.typeMapper.mapClass(serializableDescriptor)

    companion object {
        fun generateSerializableExtensions(codegen: ImplementationBodyCodegen) {
            val serializableClass = codegen.descriptor
            if (serializableClass.isInternalSerializable)
                SerializableCodegenImpl(codegen, serializableClass).generate()
        }
    }

    private val descToProps = classCodegen.myClass.bodyPropertiesDescriptorsMap(classCodegen.bindingContext)

    private val paramsToProps: Map<PropertyDescriptor, KtParameter> = classCodegen.myClass.primaryPropertiesDescriptorsMap(classCodegen.bindingContext)

    private fun getProp(prop: SerializableProperty) = descToProps[prop.descriptor]
    private fun getParam(prop: SerializableProperty) = paramsToProps[prop.descriptor]
    private val SerializableProperty.asmType get() = classCodegen.typeMapper.mapType(this.type)

    override fun generateInternalConstructor(constructorDescriptor: ClassConstructorDescriptor) {
        classCodegen.generateMethod(constructorDescriptor, { sig, expr -> doGenerateConstructorImpl(expr) })
    }

    override fun generateWriteSelfMethod(methodDescriptor: FunctionDescriptor) {
        classCodegen.generateMethod(methodDescriptor, { sig, expr -> doGenerateWriteSelf(expr, sig) })
    }

    private fun InstructionAdapter.doGenerateWriteSelf(exprCodegen: ExpressionCodegen, signature: JvmMethodSignature) {
        val thisI = 0
        val outputI = 1
        val serialDescI = 2
//        val offsetI = 3

        val superClass = serializableDescriptor.getSuperClassOrAny()
        val myPropsStart: Int
        if (superClass.isInternalSerializable) {
            myPropsStart = SerializableProperties(superClass, classCodegen.bindingContext).serializableProperties.size
            //super.writeSelf(output, serialDesc)
            load(thisI, thisAsmType)
            load(outputI, kOutputType)
            load(serialDescI, descType)
            invokespecial(classCodegen.typeMapper.mapType(superClass).internalName, signature.asmMethod.name, signature.asmMethod.descriptor, false)
        }
        else {
            myPropsStart = 0
            // offset = 0
//            iconst(0)
        }

        for (i in myPropsStart until properties.serializableProperties.size) {
            val property = properties[i]
            // output.writeXxxElementValue (desc, index, value)
            load(outputI, kOutputType)
            load(serialDescI, descType)
            iconst(i)
            genKOutputMethodCall(property, classCodegen, exprCodegen, thisAsmType, thisI)
        }

        areturn(Type.VOID_TYPE)
    }

    private fun InstructionAdapter.doGenerateConstructorImpl(exprCodegen: ExpressionCodegen) {
        val seenMask = 1
//        var propOffset = 2
        var (propIndex, propOffset) = generateSuperSerializableCall(2)
        for (i in propIndex until properties.serializableProperties.size) {
            val prop = properties[i]
            if (prop.transient) {
                if (!needInitProperty(prop)) throw CompilationException("transient without default value", null, null)
                exprCodegen.genInitProperty(prop)
                propOffset += prop.asmType.size
                continue
            }
            val propType = prop.asmType
            if (!prop.optional) {
                // primary were validated before constructor call
                genValidateProperty(i) { seenMask }
                val nonThrowLabel = Label()
                ificmpne(nonThrowLabel)
                genExceptionThrow(serializationExceptionMissingFieldName, prop.name)
                visitLabel(nonThrowLabel)
                // setting field
                load(0, thisAsmType)
                load(propOffset, propType)
                putfield(thisAsmType.internalName, prop.descriptor.name.asString(), propType.descriptor)
            }
            else {
                genValidateProperty(i) { seenMask }
                val setLbl = Label()
                val nextLabel = Label()
                ificmpeq(setLbl)
                // setting field
                // todo: validate nullability
                load(0, thisAsmType)
                load(propOffset, propType)
                putfield(thisAsmType.internalName, prop.descriptor.name.asString(), propType.descriptor)
                goTo(nextLabel)
                visitLabel(setLbl)
                // setting defaultValue
                exprCodegen.genInitProperty(prop)
                visitLabel(nextLabel)
            }
            propOffset += prop.asmType.size
        }

        // these properties required to be manually invoked, because they are not in serializableProperties
        val serializedProps = properties.serializableProperties.map { it.descriptor }

        (descToProps - serializedProps)
                .forEach { _, prop -> classCodegen.initializeProperty(exprCodegen, prop) }
        (paramsToProps - serializedProps)
                .forEach { t, u -> exprCodegen.genInitParam(t, u) }

        // init blocks
        // todo: proper order with other initializers
        classCodegen.myClass.anonymousInitializers()
                .forEach { exprCodegen.gen(it, Type.VOID_TYPE) }
        areturn(Type.VOID_TYPE)
    }

    private fun InstructionAdapter.generateSuperSerializableCall(propStartVar: Int): Pair<Int, Int> {
        val superClass = serializableDescriptor.getSuperClassOrAny()
        val superType = classCodegen.typeMapper.mapType(superClass).internalName

        load(0, thisAsmType)

        if (!superClass.isInternalSerializable) {
            require(superClass.constructors.firstOrNull { it.valueParameters.isEmpty() } != null) { "Non-serializable parent of serializable class must have no arg constructor" }

            // call
            invokespecial(superType, "<init>", "()V", false)
            return 0 to propStartVar
        }
        else {
            val superProps = SerializableProperties(superClass, classCodegen.bindingContext).serializableProperties
            val creator = buildInternalConstructorDesc(propStartVar, 1, classCodegen, superProps)
            invokespecial(superType, "<init>", creator, false)
            return superProps.size to propStartVar + superProps.sumBy { it.asmType.size }
        }
    }

    private fun needInitProperty(prop: SerializableProperty) = getProp(prop)?.let { classCodegen.shouldInitializeProperty(it) }
                                                               ?: getParam(prop)?.hasDefaultValue() ?: throw IllegalStateException()

    private fun ExpressionCodegen.genInitProperty(prop: SerializableProperty)
            = getProp(prop)?.let {
        classCodegen.initializeProperty(this, it)
    }
              ?: getParam(prop)?.let {
        this.v.load(0, thisAsmType)
        this.gen(it.defaultValue, prop.asmType)
        this.v.putfield(thisAsmType.internalName, prop.name, prop.asmType.descriptor)
    }
              ?: throw IllegalStateException()

    private fun ExpressionCodegen.genInitParam(prop: PropertyDescriptor, param: KtParameter) {
        this.v.load(0, thisAsmType)
        val mapType = classCodegen.typeMapper.mapType(prop.type)
        this.gen(param.defaultValue, mapType)
        this.v.putfield(thisAsmType.internalName, prop.name.asString(), mapType.descriptor)
    }
}