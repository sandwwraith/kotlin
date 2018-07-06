/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlinx.serialization.compiler.backend.ir

import org.jetbrains.kotlin.backend.common.BackendContext
import org.jetbrains.kotlin.backend.common.lower.createIrBuilder
import org.jetbrains.kotlin.backend.common.lower.irSetField
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.ConstructorDescriptor
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.descriptors.PropertyDescriptor
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.declarations.impl.IrFunctionImpl
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.impl.IrGetValueImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrVarargImpl
import org.jetbrains.kotlin.ir.expressions.mapValueParameters
import org.jetbrains.kotlin.ir.expressions.mapValueParametersIndexed
import org.jetbrains.kotlin.ir.symbols.impl.IrSimpleFunctionSymbolImpl
import org.jetbrains.kotlin.ir.util.createParameterDeclarations
import org.jetbrains.kotlin.ir.util.withScope
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.scopes.getDescriptorsFiltered
import org.jetbrains.kotlinx.serialization.compiler.backend.common.SerializerCodegen
import org.jetbrains.kotlinx.serialization.compiler.resolve.getClassFromInternalSerializationPackage
import org.jetbrains.kotlinx.serialization.compiler.resolve.getClassFromSerializationPackage
import org.jetbrains.kotlinx.serialization.compiler.resolve.getSerializableClassDescriptorBySerializer

// Is creating synthetic origin is a good idea or not?
object SERIALIZABLE_PLUGIN_ORIGIN : IrDeclarationOriginImpl("SERIALIZER")

class SerializerIrGenerator(val irClass: IrClass, val compilerContext: BackendContext, bindingContext: BindingContext) :
    SerializerCodegen(irClass.descriptor, bindingContext) {

    private fun contributeFunction(descriptor: FunctionDescriptor, bodyGen: IrBlockBodyBuilder.(IrFunction) -> Unit) {
        // did I forget something here or everything is ok?
        val f = IrFunctionImpl(irClass.startOffset, irClass.endOffset, SERIALIZABLE_PLUGIN_ORIGIN, descriptor)
        f.createParameterDeclarations()
        f.body = compilerContext.createIrBuilder(f.symbol).irBlockBody { bodyGen(f) }
        irClass.addMember(f)
    }

    override fun generateSerialDesc() {
        val desc: PropertyDescriptor = serialDescPropertyDescriptor ?: return
        val serialDescImplClass = serializerDescriptor
            .getClassFromInternalSerializationPackage("SerialClassDescImpl")
        val serialDescImplConstructor = serialDescImplClass
            .unsubstitutedPrimaryConstructor!!

        val addFunc = serialDescImplClass.getFuncDesc("addElement").single()
        val pushFunc = serialDescImplClass.getFuncDesc("pushAnnotation").single()
        val pushClassFunc = serialDescImplClass.getFuncDesc("pushClassAnnotation").single()

        val thisAsReceiverParameter = irClass.thisReceiver!!
        lateinit var prop: IrProperty

        // how to (auto)create backing field and getter/setter?
        compilerContext.symbolTable.withScope(irClass.descriptor) {

            introduceValueParameter(thisAsReceiverParameter)
            prop = compilerContext.generateSimplePropertyWithBackingField(thisAsReceiverParameter.symbol, desc)
            irClass.addMember(prop)
        }

        compilerContext.symbolTable.declareAnonymousInitializer(
            irClass.startOffset, irClass.endOffset, SERIALIZABLE_PLUGIN_ORIGIN, irClass.descriptor
        ).buildWithScope(compilerContext) { initIrBody ->
            val ctor = irClass.declarations.filterIsInstance<IrConstructor>().singleOrNull()
            val serialClassDescImplCtor = compilerContext.symbolTable.referenceFunction(serialDescImplConstructor)
            val addFuncS = compilerContext.symbolTable.referenceFunction(addFunc)
            compilerContext.symbolTable.withScope(initIrBody.descriptor) {
                initIrBody.body = compilerContext.createIrBuilder(initIrBody.symbol).irBlockBody {
                    val value = irTemporary(irCall(serialClassDescImplCtor).mapValueParameters { irString(serialName) }, "serialDesc")

                    fun addFieldCall(fieldName: String) =
                        irCall(addFuncS).apply { dispatchReceiver = irGet(value.symbol) }.mapValueParameters { irString(fieldName) }

                    for (classProp in orderedProperties) {
                        if (classProp.transient) continue
                        +addFieldCall(classProp.name)
                        // serialDesc.pushAnnotation(...) todo
                    }
                    +irSetField(
                        compilerContext.generateReceiverExpressionForFieldAccess(
                            thisAsReceiverParameter.symbol,
                            serialDescPropertyDescriptor
                        ),
                        prop.backingField!!.symbol,
                        irGet(value.symbol)
                    )
                }
                // workaround for KT-25353
//                irClass.addMember(initIrBody)
                (ctor?.body as? IrBlockBody)?.statements?.addAll(initIrBody.body.statements)
            }
        }
    }

    override fun generateGenericFieldsAndConstructor(typedConstructorDescriptor: ConstructorDescriptor) {
//        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun generateSerializableClassProperty(property: PropertyDescriptor) {
//        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    private fun ClassDescriptor.getFuncDesc(funcName: String) =
        unsubstitutedMemberScope.getDescriptorsFiltered { it == Name.identifier(funcName) }.asSequence().filterIsInstance<FunctionDescriptor>()

    override fun generateSave(function: FunctionDescriptor) = contributeFunction(function) { saveFunc ->

        fun irThis(): IrExpression =
            IrGetValueImpl(startOffset, endOffset, saveFunc.dispatchReceiverParameter!!.symbol)

        val kOutputClass = serializerDescriptor.getClassFromSerializationPackage("Encoder")

//        val member = irClass.declarations.first { it is IrProperty } as IrProperty //todo: better lookup
//        val descriptorGetterSymbol = member.getter?.symbol!!
        val descriptorGetterSymbol = IrSimpleFunctionSymbolImpl(serialDescPropertyDescriptor?.getter!!)

        //  fun beginStructure(desc: SerialDescriptor, vararg typeParams: KSerializer<*>): StructureEncoder

        // is this ok to create symbol on-the-fly from external (from another library) descriptor?
        val beginFunc = IrSimpleFunctionSymbolImpl(
            kOutputClass.getFuncDesc("beginStructure").single()
        )

        val call = irCall(beginFunc).mapValueParametersIndexed { i, parameterDescriptor ->
            if (i == 0) irGet(irThis(), descriptorGetterSymbol) else IrVarargImpl(
                startOffset,
                endOffset,
                parameterDescriptor.type,
                parameterDescriptor.varargElementType!!
            )
        }
        // can it be done in more concise way? e.g. additional builder function?
        call.dispatchReceiver = irGet(saveFunc.valueParameters[0].symbol)
        val localOutput = irTemporary(call, "output")
        +irReturn(irGet(localOutput.symbol))
        // ...
    }

    override fun generateLoad(function: FunctionDescriptor) {
//        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    companion object {
        fun generate(
            irClass: IrClass,
            context: BackendContext,
            bindingContext: BindingContext
        ) {
            if (getSerializableClassDescriptorBySerializer(irClass.descriptor) != null)
                SerializerIrGenerator(irClass, context, bindingContext).generate()
        }
    }
}