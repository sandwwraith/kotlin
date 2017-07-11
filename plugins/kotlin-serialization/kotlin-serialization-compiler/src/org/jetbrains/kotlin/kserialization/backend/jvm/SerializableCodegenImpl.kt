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

package org.jetbrains.kotlin.kserialization.backend.jvm

import org.jetbrains.kotlin.codegen.CompilationException
import org.jetbrains.kotlin.codegen.ExpressionCodegen
import org.jetbrains.kotlin.codegen.ImplementationBodyCodegen
import org.jetbrains.kotlin.descriptors.ClassConstructorDescriptor
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.PropertyDescriptor
import org.jetbrains.kotlin.kserialization.backend.common.SerializableCodegen
import org.jetbrains.kotlin.kserialization.resolve.SerializableProperty
import org.jetbrains.kotlin.kserialization.resolve.isDefaultSerializable
import org.jetbrains.kotlin.psi.KtAnonymousInitializer
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.org.objectweb.asm.Label
import org.jetbrains.org.objectweb.asm.Type
import org.jetbrains.org.objectweb.asm.commons.InstructionAdapter

/**
 *  @author Leonid Startsev
 *          sandwwraith@gmail.com
 */

class SerializableCodegenImpl(
        private val classCodegen: ImplementationBodyCodegen,
        serializableClass: ClassDescriptor
) : SerializableCodegen(classCodegen.myClass, classCodegen.bindingContext) {

    private val thisAsmType = classCodegen.typeMapper.mapClass(serializableDescriptor)

    companion object {
        fun generateSerializableExtensions(codegen: ImplementationBodyCodegen) {
            val serializableClass = codegen.descriptor
            if (serializableClass.isDefaultSerializable)
                SerializableCodegenImpl(codegen, serializableClass).generate()
        }
    }

    private val descToProps = classCodegen.myClass.declarations
            .asSequence()
            .filterIsInstance<KtProperty>()
            .associateBy { classCodegen.bindingContext[BindingContext.VARIABLE, it]!! }

    private val paramsToProps: Map<PropertyDescriptor, KtParameter> = classCodegen.primaryConstructorParameters
            .asSequence()
            .filter { it.hasValOrVar() }
            .associateBy { classCodegen.bindingContext[BindingContext.PRIMARY_CONSTRUCTOR_PARAMETER, it]!! }

    private fun getProp(prop: SerializableProperty) = descToProps[prop.descriptor]
    private fun getParam(prop: SerializableProperty) = paramsToProps[prop.descriptor]
    private val SerializableProperty.asmType get() = classCodegen.typeMapper.mapType(this.type)

    override fun generateInternalConstructor(constructorDescriptor: ClassConstructorDescriptor) {
        classCodegen.generateMethod(constructorDescriptor, { sig, expr -> doGenerateConstructorImpl(expr) })
    }

    private fun InstructionAdapter.doGenerateConstructorImpl(exprCodegen: ExpressionCodegen) {
        load(0, thisAsmType)
        invokespecial("java/lang/Object", "<init>", "()V", false)
//        getstatic("java/lang/System", "out", "Ljava/io/PrintStream;")
//        aconst("I'm a generated method")
//        invokevirtual("java/io/PrintStream", "println", "(Ljava/lang/Object;)V", false)
        classCodegen.myClass.declarations
        val seenMask = 1
        var propVar = 2
        for ((i, prop) in properties.serializableProperties.withIndex()) {
            if (prop.transient) {
                if (!needInitProperty(prop)) throw CompilationException("transient without default value", null, null)
                exprCodegen.genInitProperty(prop)
                propVar += prop.asmType.size
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
                load(propVar, propType)
                putfield(thisAsmType.internalName, prop.name, propType.descriptor)
            }
            else {
                genValidateProperty(i) { seenMask }
                val setLbl = Label()
                val nextLabel = Label()
                ificmpeq(setLbl)
                // setting field
                load(0, thisAsmType)
                load(propVar, propType)
                putfield(thisAsmType.internalName, prop.name, propType.descriptor)
                goTo(nextLabel)
                visitLabel(setLbl)
                // setting defaultValue
                exprCodegen.genInitProperty(prop)
                visitLabel(nextLabel)
            }
            propVar += prop.asmType.size
        }

        // these properties required to be manually invoked, because they are not in serializableProperties
        val serializedProps = properties.serializableProperties.map { it.descriptor }

        (descToProps - serializedProps)
                .forEach { _, prop -> classCodegen.initializeProperty(exprCodegen, prop) }
        (paramsToProps - serializedProps)
                .forEach { t, u -> exprCodegen.genInitParam(t, u) }

        // init blocks
        classCodegen.myClass.declarations
                .asSequence()
                .filterIsInstance<KtAnonymousInitializer>()
                .mapNotNull { it.body }
                .forEach { exprCodegen.gen(it, Type.VOID_TYPE) }
        areturn(Type.VOID_TYPE)
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