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

import org.jetbrains.kotlin.codegen.ExpressionCodegen
import org.jetbrains.kotlin.codegen.FunctionGenerationStrategy
import org.jetbrains.kotlin.codegen.ImplementationBodyCodegen
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.resolve.jvm.diagnostics.OtherOrigin
import org.jetbrains.kotlin.resolve.jvm.jvmSignature.JvmMethodSignature
import org.jetbrains.org.objectweb.asm.Type
import org.jetbrains.org.objectweb.asm.commons.InstructionAdapter

/**
 *  @author Leonid Startsev
 *          sandwwraith@gmail.com
 */

val serializationExceptionName = "kotlin/serialization/SerializationException"
val serializationExceptionMissingFieldName = "kotlin/serialization/MissingFieldException"

val OPT_MASK_TYPE: Type = Type.INT_TYPE
val OPT_MASK_BITS = 32

// compare with zero. if result == 0, property was not seen.
fun InstructionAdapter.genValidateProperty(index: Int, bitMaskPos: (Int) -> Int) {
    val addr = bitMaskPos(index)
    load(addr, OPT_MASK_TYPE)
    iconst(1 shl (index % OPT_MASK_BITS))
    and(OPT_MASK_TYPE)
    iconst(0)
}

fun InstructionAdapter.genExceptionThrow(exceptionClass: String, message: String) {
    anew(Type.getObjectType(exceptionClass))
    dup()
    aconst(message)
    invokespecial(exceptionClass, "<init>", "(Ljava/lang/String;)V", false)
    checkcast(Type.getObjectType("java/lang/Throwable"))
    athrow()
}

fun ImplementationBodyCodegen.generateMethod(function: FunctionDescriptor,
                                             block: InstructionAdapter.(JvmMethodSignature, ExpressionCodegen) -> Unit) {
    this.functionCodegen.generateMethod(OtherOrigin(this.myClass, function), function,
                                        object : FunctionGenerationStrategy.CodegenBased(this.state) {
                                            override fun doGenerateBody(codegen: ExpressionCodegen, signature: JvmMethodSignature) {
                                                codegen.v.block(signature, codegen)
                                            }
                                        })
}