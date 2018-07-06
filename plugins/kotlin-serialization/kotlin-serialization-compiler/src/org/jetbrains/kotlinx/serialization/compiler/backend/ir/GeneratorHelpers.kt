/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlinx.serialization.compiler.backend.ir

import org.jetbrains.kotlin.backend.common.BackendContext
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.impl.IrFieldImpl
import org.jetbrains.kotlin.ir.declarations.impl.IrFunctionImpl
import org.jetbrains.kotlin.ir.declarations.impl.IrPropertyImpl
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.impl.*
import org.jetbrains.kotlin.ir.symbols.IrFieldSymbol
import org.jetbrains.kotlin.ir.symbols.IrValueSymbol
import org.jetbrains.kotlin.ir.util.createParameterDeclarations
import org.jetbrains.kotlin.ir.util.withScope

/*
 This file is mainly copied from FunctionGenerator.
 However, I can't use it's directly because all generateSomething methods require KtProperty (psi element)
 Also, FunctionGenerator itself has DeclarationGenerator as ctor param, which is a part of psi2ir
 (it can be instantiated here, but I don't know how good is that idea)
 */

inline fun <T : IrDeclaration> T.buildWithScope(context: BackendContext, builder: (T) -> Unit): T =
    also { irDeclaration ->
        context.symbolTable.withScope(irDeclaration.descriptor) {
            builder(irDeclaration)
        }
    }

fun BackendContext.generateSimplePropertyWithBackingField(ownerSymbol: IrValueSymbol, propertyDescriptor: PropertyDescriptor): IrProperty {
    val irProperty = IrPropertyImpl(
        UNDEFINED_OFFSET, UNDEFINED_OFFSET,
        SERIALIZABLE_PLUGIN_ORIGIN, false,
        propertyDescriptor
    )
    irProperty.backingField = generatePropertyBackingField(propertyDescriptor)
    val fieldSymbol = irProperty.backingField!!.symbol
    irProperty.getter = propertyDescriptor.getter?.let { generatePropertyAccessor(it, fieldSymbol, ownerSymbol) }//?.apply { symbol.bind() }
    irProperty.setter = propertyDescriptor.setter?.let { generatePropertyAccessor(it, fieldSymbol, ownerSymbol) }
    return irProperty
}

fun BackendContext.generatePropertyBackingField(propertyDescriptor: PropertyDescriptor): IrField {
    return IrFieldImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, SERIALIZABLE_PLUGIN_ORIGIN, propertyDescriptor)
}

fun BackendContext.generatePropertyAccessor(
    descriptor: PropertyAccessorDescriptor,
    fieldSymbol: IrFieldSymbol,
    ownerSymbol: IrValueSymbol
): IrSimpleFunction {
    val irAccessor = IrFunctionImpl(
        UNDEFINED_OFFSET, UNDEFINED_OFFSET,
        SERIALIZABLE_PLUGIN_ORIGIN,
        descriptor
    ) // is it mandatory to add something to .overridenSymbols??
    symbolTable.withScope(irAccessor.descriptor) {
        irAccessor.createParameterDeclarations()
        irAccessor.body = when (descriptor) {
            is PropertyGetterDescriptor -> generateDefaultGetterBody(descriptor, irAccessor, fieldSymbol, ownerSymbol)
            is PropertySetterDescriptor -> generateDefaultSetterBody(descriptor, irAccessor, fieldSymbol, ownerSymbol)
            else -> throw AssertionError("Should be getter or setter: $descriptor")
        }
    }
    return irAccessor
}

private fun BackendContext.generateDefaultGetterBody(
    getter: PropertyGetterDescriptor,
    irAccessor: IrSimpleFunction,
    fieldSymbol: IrFieldSymbol,
    ownerSymbol: IrValueSymbol
): IrBlockBody {
    val property = getter.correspondingProperty

    val irBody = IrBlockBodyImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET)

    val receiver = generateReceiverExpressionForFieldAccess(ownerSymbol, property)

    irBody.statements.add(
        IrReturnImpl(
            UNDEFINED_OFFSET, UNDEFINED_OFFSET, this.builtIns.nothingType,
            irAccessor.symbol,
            IrGetFieldImpl(
                UNDEFINED_OFFSET, UNDEFINED_OFFSET,
                fieldSymbol,
                receiver
            )
        )
    )
    return irBody
}

private fun BackendContext.generateDefaultSetterBody(
    setter: PropertySetterDescriptor,
    irAccessor: IrSimpleFunction,
    fieldSymbol: IrFieldSymbol,
    ownerSymbol: IrValueSymbol
): IrBlockBody {
    val property = setter.correspondingProperty

    val irBody = IrBlockBodyImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET)

    val receiver = generateReceiverExpressionForFieldAccess(ownerSymbol, property)

    val setterParameter = irAccessor.valueParameters.single().symbol
    irBody.statements.add(
        IrSetFieldImpl(
            UNDEFINED_OFFSET, UNDEFINED_OFFSET,
            fieldSymbol,
            receiver,
            IrGetValueImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, setterParameter)
        )
    )
    return irBody
}

fun BackendContext.generateReceiverExpressionForFieldAccess(
    ownerSymbol: IrValueSymbol,
    property: PropertyDescriptor
): IrExpression {
    val containingDeclaration = property.containingDeclaration
    return when (containingDeclaration) {
        is ClassDescriptor ->
            IrGetValueImpl(
                UNDEFINED_OFFSET, UNDEFINED_OFFSET,
                // can use ownerSymbol here if passing symbol table is undesirable
//                symbolTable.referenceValue(containingDeclaration.thisAsReceiverParameter)
                ownerSymbol
            )
        else -> throw AssertionError("Property must be in class")
    }
}