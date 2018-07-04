/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.common.extensions

import org.jetbrains.kotlin.backend.common.BackendContext
import org.jetbrains.kotlin.extensions.ProjectExtensionDescriptor
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.resolve.BindingContext

interface IrLoweringExtension {
    companion object :
        ProjectExtensionDescriptor<IrLoweringExtension>("org.jetbrains.kotlin.irLoweringExtension", IrLoweringExtension::class.java)

    /**
     * Invoked before all other lowerings
     */
    fun lowerFirst(
        file: IrFile,
        backendContext: BackendContext,
        bindingContext: BindingContext
    )
}