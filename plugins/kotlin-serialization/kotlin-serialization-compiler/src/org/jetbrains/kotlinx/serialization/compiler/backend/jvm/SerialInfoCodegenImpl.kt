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

import org.jetbrains.kotlin.codegen.ImplementationBodyCodegen
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.descriptors.annotations.Annotations
import org.jetbrains.kotlin.descriptors.impl.ClassConstructorDescriptorImpl
import org.jetbrains.kotlin.descriptors.impl.SimpleFunctionDescriptorImpl
import org.jetbrains.kotlin.descriptors.impl.ValueParameterDescriptorImpl
import org.jetbrains.kotlinx.serialization.compiler.resolve.KSerializerDescriptorResolver
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.DescriptorUtils
import org.jetbrains.kotlin.resolve.jvm.diagnostics.OtherOrigin
import org.jetbrains.kotlin.resolve.scopes.getDescriptorsFiltered
import org.jetbrains.kotlinx.serialization.compiler.backend.common.SerialImplCodegen
import org.jetbrains.org.objectweb.asm.Opcodes
import org.jetbrains.org.objectweb.asm.Type

class SerialInfoCodegenImpl(val codegen: ImplementationBodyCodegen, bindingContext: BindingContext)
    : SerialImplCodegen(codegen.myClass, bindingContext) {
    val thisAsmType = codegen.typeMapper.mapClass(thisClass)

    override fun generateFieldsAndSetters(props: List<PropertyDescriptor>) {
        props.forEach { prop ->
            val propType = codegen.typeMapper.mapType(prop.type)
            // backing field
            val propFieldName = "_" + prop.name.identifier
            codegen.v.newField(OtherOrigin(codegen.myClass.psiOrParent), Opcodes.ACC_PRIVATE or Opcodes.ACC_FINAL or Opcodes.ACC_SYNTHETIC,
                               propFieldName, propType.descriptor, null, null)

            // annotation classes properties don't have prefix 'get'
            val f = if (DescriptorUtils.isAnnotationClass(thisClass.containingDeclaration)) {
                SimpleFunctionDescriptorImpl.create(thisClass, Annotations.EMPTY, prop.name, CallableMemberDescriptor.Kind.SYNTHESIZED, thisClass.source)
                        .apply { initialize(null, thisClass.thisAsReceiverParameter, emptyList(), emptyList(), prop.type, Modality.FINAL, Visibilities.PUBLIC) }
            }
            else prop.getter!!
            codegen.generateMethod(f, { _, _ ->
                load(0, thisAsmType)
                getfield(thisAsmType.internalName, propFieldName, propType.descriptor)
                areturn(propType)
            })
        }
    }

    override fun generateConstructor(props: List<PropertyDescriptor>) {
        val constr = createSyntheticImplConstructorDescriptor(props)

        codegen.generateMethod(constr, { _, _ ->
            load(0, thisAsmType)
            invokespecial("java/lang/Object", "<init>", "()V", false)
            var varOffset = 1
            props.forEach { prop ->
                val propType = codegen.typeMapper.mapType(prop.type)
                val propFieldName = "_" + prop.name.identifier
                load(0, thisAsmType)
                load(varOffset, propType)
                putfield(thisAsmType.internalName, propFieldName, propType.descriptor)
                varOffset += propType.size
            }
            areturn(Type.VOID_TYPE)
        })
    }

    companion object {
        fun generateSerialInfoImplBody(codegen: ImplementationBodyCodegen) {
            val thisClass = codegen.descriptor
            if (KSerializerDescriptorResolver.isSerialInfoImpl(thisClass))
                SerialInfoCodegenImpl(codegen, codegen.bindingContext).generate()
        }
    }
}

