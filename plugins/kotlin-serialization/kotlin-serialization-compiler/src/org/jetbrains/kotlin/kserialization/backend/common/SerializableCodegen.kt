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

package org.jetbrains.kotlin.kserialization.backend.common

import org.jetbrains.kotlin.descriptors.ClassConstructorDescriptor
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.kserialization.resolve.KSerializerDescriptorResolver
import org.jetbrains.kotlin.kserialization.resolve.SerializableProperties
import org.jetbrains.kotlin.psi.KtPureClassOrObject
import org.jetbrains.kotlin.psi.synthetics.findClassDescriptor
import org.jetbrains.kotlin.resolve.BindingContext

/**
 *  @author Leonid Startsev
 *          sandwwraith@gmail.com
 */

abstract class SerializableCodegen(declaration: KtPureClassOrObject, private val bindingContext: BindingContext) {
    protected val serializableDescriptor: ClassDescriptor = declaration.findClassDescriptor(bindingContext)
    protected val properties = SerializableProperties(serializableDescriptor, bindingContext)

    fun generate() {
        generateSyntheticInternalConstructorIfNeeded()
    }

    private fun generateSyntheticInternalConstructorIfNeeded() {
        val constrDesc = KSerializerDescriptorResolver.createLoadConstructorDescriptor(serializableDescriptor, bindingContext)
        generateInternalConstructor(constrDesc)
    }

    protected abstract fun generateInternalConstructor(constructorDescriptor: ClassConstructorDescriptor)
}