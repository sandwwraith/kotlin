/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlinx.serialization.compiler.extensions

import org.jetbrains.kotlin.backend.common.BackendContext
import org.jetbrains.kotlin.backend.common.ClassLoweringPass
import org.jetbrains.kotlin.backend.common.extensions.IrLoweringExtension
import org.jetbrains.kotlin.backend.common.runOnFilePostfix
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlinx.serialization.compiler.backend.ir.SerializableCompanionIrGenerator
import org.jetbrains.kotlinx.serialization.compiler.backend.ir.SerializerIrGenerator

private class SerializerClassLowering(
    val context: BackendContext,
    val bindingContext: BindingContext
) :
    IrElementTransformerVoid(), ClassLoweringPass {
    override fun lower(irClass: IrClass) {
        SerializerIrGenerator.generate(irClass, context, bindingContext)
        SerializableCompanionIrGenerator.generate(irClass, context, bindingContext)
    }
}

class SerializationLoweringExtension : IrLoweringExtension {
    override fun lowerFirst(
        file: IrFile,
        backendContext: BackendContext,
        bindingContext: BindingContext
    ) {
        SerializerClassLowering(backendContext, bindingContext).runOnFilePostfix(file)
    }
}