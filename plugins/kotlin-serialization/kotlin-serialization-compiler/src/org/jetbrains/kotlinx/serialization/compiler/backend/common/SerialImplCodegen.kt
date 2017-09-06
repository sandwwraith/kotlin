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

package org.jetbrains.kotlinx.serialization.compiler.backend.common

import org.jetbrains.kotlin.descriptors.ClassConstructorDescriptor
import org.jetbrains.kotlin.descriptors.PropertyDescriptor
import org.jetbrains.kotlin.descriptors.ValueParameterDescriptor
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.descriptors.annotations.Annotations
import org.jetbrains.kotlin.descriptors.impl.ClassConstructorDescriptorImpl
import org.jetbrains.kotlin.descriptors.impl.ValueParameterDescriptorImpl
import org.jetbrains.kotlin.psi.KtPureClassOrObject
import org.jetbrains.kotlin.psi.synthetics.findClassDescriptor
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.scopes.getDescriptorsFiltered

abstract class SerialImplCodegen(val declaration: KtPureClassOrObject, val bindingContext: BindingContext) {
    protected val thisClass = declaration.findClassDescriptor(bindingContext)

    fun generate() {
        val props = thisClass.unsubstitutedMemberScope.getDescriptorsFiltered().filterIsInstance<PropertyDescriptor>()
        generateFieldsAndSetters(props)
        generateConstructor(props)
    }

    protected abstract fun generateConstructor(props: List<PropertyDescriptor>)

    protected abstract fun generateFieldsAndSetters(props: List<PropertyDescriptor>)

    protected fun createSyntheticImplConstructorDescriptor(props: List<PropertyDescriptor>): ClassConstructorDescriptor {
        val constr = ClassConstructorDescriptorImpl.createSynthesized(
                thisClass,
                Annotations.EMPTY,
                false,
                thisClass.source
        )
        val args = mutableListOf<ValueParameterDescriptor>()
        var i = 0
        props.forEach { prop ->
            args.add(ValueParameterDescriptorImpl(constr, null, i++, Annotations.EMPTY, prop.name, prop.type, false, false, false, null, constr.source))
        }
        constr.initialize(
                args,
                Visibilities.PUBLIC
        )

        constr.returnType = thisClass.defaultType
        return constr
    }
}