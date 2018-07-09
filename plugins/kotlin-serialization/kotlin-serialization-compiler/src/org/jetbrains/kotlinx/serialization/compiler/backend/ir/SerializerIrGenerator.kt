/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlinx.serialization.compiler.backend.ir

import org.jetbrains.kotlin.backend.common.BackendContext
import org.jetbrains.kotlin.backend.common.lower.createIrBuilder
import org.jetbrains.kotlin.backend.common.lower.irGetField
import org.jetbrains.kotlin.backend.common.lower.irSetField
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.declarations.impl.IrFunctionImpl
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.IrGetObjectValueImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrGetValueImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrVarargImpl
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.createParameterDeclarations
import org.jetbrains.kotlin.ir.util.withScope
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.descriptorUtil.classId
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlinx.serialization.compiler.backend.common.SerializerCodegen
import org.jetbrains.kotlinx.serialization.compiler.backend.common.findTypeSerializerOrContext
import org.jetbrains.kotlinx.serialization.compiler.backend.common.getSerialTypeInfo
import org.jetbrains.kotlinx.serialization.compiler.backend.jvm.contextSerializerId
import org.jetbrains.kotlinx.serialization.compiler.backend.jvm.enumSerializerId
import org.jetbrains.kotlinx.serialization.compiler.backend.jvm.referenceArraySerializerId
import org.jetbrains.kotlinx.serialization.compiler.resolve.genericIndex
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

    private fun IrBuilderWithScope.irInvoke(
        dispatchReceiver: IrExpression? = null,
        callee: IrFunctionSymbol,
        vararg args: IrExpression
    ): IrCall {
        val call = irCall(callee)
        call.dispatchReceiver = dispatchReceiver
        args.forEachIndexed(call::putValueArgument)
        return call
    }

    private fun IrBuilderWithScope.irGetObject(classDescriptor: ClassDescriptor) =
        IrGetObjectValueImpl(
            startOffset,
            endOffset,
            classDescriptor.defaultType,
            compilerContext.symbolTable.referenceClass(classDescriptor)
        )

    private fun IrBuilderWithScope.serializerInstance(
        serializerClass: ClassDescriptor?,
        module: ModuleDescriptor,
        kType: KotlinType,
        genericIndex: Int? = null
    ): IrExpression? {
        val nullableSerClass =
            compilerContext.symbolTable.referenceClass(module.getClassFromInternalSerializationPackage("NullableSerializer"))
        if (serializerClass == null) {
            if (genericIndex == null) return null
            return TODO("Saved serializer for generic argument")
        }
        if (serializerClass.kind == ClassKind.OBJECT) {
            return irGetObject(serializerClass)
        } else {
            var args = if (serializerClass.classId == enumSerializerId || serializerClass.classId == contextSerializerId)
                TODO(".getKClass for enum and context serializer")
            else kType.arguments.map {
                val argSer = findTypeSerializerOrContext(module, it.type)
                val expr = serializerInstance(argSer, module, it.type, it.type.genericIndex) ?: return null
                // todo: smth better than constructors[0]
                if (it.type.isMarkedNullable) irInvoke(null, nullableSerClass.constructors.toList()[0], expr) else expr
            }
            if (serializerClass.classId == referenceArraySerializerId)
                args = TODO(".getKClass for argument of reference array serializer")
            val serializable = getSerializableClassDescriptorBySerializer(serializerClass)
            val ref = if (serializable?.declaredTypeParameters?.isNotEmpty() == true) {
                TODO("Generic user serializers")
            } else {
                irInvoke(
                    null,
                    compilerContext.symbolTable.referenceConstructor(serializerClass.unsubstitutedPrimaryConstructor!!),
                    *args.toTypedArray()
                )
            }
            return ref
        }
    }

    fun ClassDescriptor.referenceMethod(methodName: String) =
        getFuncDesc(methodName).single().let { compilerContext.symbolTable.referenceFunction(it) }

    override fun generateSave(function: FunctionDescriptor) = contributeFunction(function) { saveFunc ->

        fun irThis(): IrExpression =
            IrGetValueImpl(startOffset, endOffset, saveFunc.dispatchReceiverParameter!!.symbol)

        val kOutputClass = serializerDescriptor.getClassFromSerializationPackage("StructureEncoder")

        val descriptorGetterSymbol = compilerContext.symbolTable.referenceFunction(serialDescPropertyDescriptor?.getter!!)

        val localSerialDesc = irTemporary(irGet(irThis(), descriptorGetterSymbol), "desc")

        //  fun beginStructure(desc: SerialDescriptor, vararg typeParams: KSerializer<*>): StructureEncoder

        // is this ok to create symbol on-the-fly from external (from another library) descriptor?
        val beginFunc = kOutputClass.referenceMethod("beginStructure")

        val call = irCall(beginFunc).mapValueParametersIndexed { i, parameterDescriptor ->
            if (i == 0) irGet(localSerialDesc.symbol) else IrVarargImpl(
                startOffset,
                endOffset,
                parameterDescriptor.type,
                parameterDescriptor.varargElementType!!
            )
        }
        // can it be done in more concise way? e.g. additional builder function?
        call.dispatchReceiver = irGet(saveFunc.valueParameters[0].symbol)
        val serialObjectSymbol = saveFunc.valueParameters[1].symbol
        val localOutput = irTemporary(call, "output")
//        +irReturn(irGet(localOutput.symbol))
        // ...

        // todo: internal serialization via virtual calls
        val labeledProperties = orderedProperties.filter { !it.transient }
        for (index in labeledProperties.indices) {
            val property = labeledProperties[index]
            if (property.transient) continue
            // output.writeXxxElementValue(classDesc, index, value)
            val sti = getSerialTypeInfo(property)
            val innerSerial = serializerInstance(sti.serializer, property.module, property.type, property.genericIndex)
            if (innerSerial == null) {
                val writeFunc =
                    kOutputClass.referenceMethod("encode${sti.elementMethodPrefix}ElementValue")
                +irInvoke(
                    irGet(localOutput.symbol),
                    writeFunc,
                    irGet(localSerialDesc.symbol),
                    irInt(index),
                    // todo: direct field access?
//                    irInvoke(irGet(serialObjectSymbol), compilerContext.symbolTable.referenceFunction(property.descriptor.getter!!))
                    irGetField(irGet(serialObjectSymbol), compilerContext.symbolTable.referenceField(property.descriptor))
                )
            } else {
//                TODO("Complex serializers")
                val writeFunc = kOutputClass.referenceMethod("encode${sti.elementMethodPrefix}SerializableElementValue")
                +irInvoke(
                    irGet(localOutput.symbol),
                    writeFunc,
                    irGet(localSerialDesc.symbol),
                    irInt(index),
                    innerSerial,
                    // todo: direct field access?
//                    irInvoke(irGet(serialObjectSymbol), compilerContext.symbolTable.referenceFunction(property.descriptor.getter!!))
                    irGetField(irGet(serialObjectSymbol), compilerContext.symbolTable.referenceField(property.descriptor))
                )
            }
        }

        // output.writeEnd(serialClassDesc)
        val wEndFunc = kOutputClass.getFuncDesc("endStructure").single()
            .let { compilerContext.symbolTable.referenceFunction(it) }
        +irInvoke(irGet(localOutput.symbol), wEndFunc, irGet(localSerialDesc.symbol))
//        +JsInvocation(JsNameRef(wEndFunc, localOutputRef), serialClassDescRef).makeStmt()
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