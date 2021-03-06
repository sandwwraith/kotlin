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

import org.jetbrains.kotlin.descriptors.PropertyDescriptor
import org.jetbrains.kotlin.resolve.descriptorUtil.module

class SerializableProperty(val descriptor: PropertyDescriptor, val isConstructorParameterWithDefault: Boolean) {
    val name = descriptor.annotations.serialNameValue ?: descriptor.name.asString()
    val type = descriptor.type
    val module = descriptor.module
    val serializer = descriptor.propertySerializer
    val optional = descriptor.annotations.serialOptional
    val transient = descriptor.annotations.serialTransient
    val annotations = descriptor.annotations
            .mapNotNull { it.type.toClassDescriptor }
            .filter { it.annotations.hasAnnotation(serialInfoFqName) }
}
