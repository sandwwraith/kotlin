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

package org.jetbrains.kotlinx.serialization.compiler.backend.js

import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.PropertyDescriptor
import org.jetbrains.kotlin.js.backend.ast.JsVars
import org.jetbrains.kotlin.js.translate.context.Namer
import org.jetbrains.kotlin.js.translate.context.TranslationContext
import org.jetbrains.kotlin.js.translate.declaration.DeclarationBodyVisitor
import org.jetbrains.kotlin.js.translate.declaration.DefaultPropertyTranslator
import org.jetbrains.kotlin.js.translate.utils.TranslationUtils
import org.jetbrains.kotlin.psi.KtPureClassOrObject
import org.jetbrains.kotlin.resolve.DescriptorUtils
import org.jetbrains.kotlinx.serialization.compiler.backend.common.SerialImplCodegen
import org.jetbrains.kotlinx.serialization.compiler.resolve.KSerializerDescriptorResolver
import org.jetbrains.kotlinx.serialization.compiler.resolve.isInternalSerializable

class SerialImplJsTranslator(declaration: KtPureClassOrObject,
                             val translator: DeclarationBodyVisitor,
                             val context: TranslationContext) : SerialImplCodegen(declaration, context.bindingContext()) {
    override fun generateConstructor(props: List<PropertyDescriptor>) {
        val constructorDescriptor = createSyntheticImplConstructorDescriptor(props)

        val f = context.buildFunction(constructorDescriptor) { jsFun, context ->
            val thiz = jsFun.scope.declareName(Namer.ANOTHER_THIS_PARAMETER_NAME).makeRef()
            @Suppress("NAME_SHADOWING")
            val context = context.innerContextWithAliased(thisClass, thiz)

            +JsVars(JsVars.JsVar(thiz.name, Namer.createObjectWithPrototypeFrom(context.getQualifiedReference(thisClass))))

            for ((index, prop) in props.withIndex()) {
                val paramRef = jsFun.parameters[index].name.makeRef()
                +TranslationUtils.assignmentToBackingField(context, prop, paramRef).makeStmt()
            }
        }
        f.name = context.getInnerNameForDescriptor(constructorDescriptor)
        context.addDeclarationStatement(f.makeStmt())
    }

    override fun generateFieldsAndSetters(props: List<PropertyDescriptor>) {
        for (propDesc in props) {
            val propTranslator = DefaultPropertyTranslator(propDesc, context,
                                                           translator.getBackingFieldReference(propDesc))
            val getterDesc = propDesc.getter!!
            val getterExpr = context.getFunctionObject(getterDesc)
                    .apply { propTranslator.generateDefaultGetterFunction(getterDesc, this) }
            translator.addProperty(propDesc, getterExpr, null)
        }
    }

    companion object {
        fun translate(declaration: KtPureClassOrObject, descriptor: ClassDescriptor, translator: DeclarationBodyVisitor, context: TranslationContext) {
            // no need to synthetic implementation of annotation classes
            if (KSerializerDescriptorResolver.isSerialInfoImpl(descriptor) && !DescriptorUtils.isAnnotationClass(descriptor.containingDeclaration))
                SerialImplJsTranslator(declaration, translator, context).generate()
        }
    }
}