package org.jetbrains.kotlin.backend.konan.lower

import org.jetbrains.kotlin.backend.common.ClassLoweringPass
import org.jetbrains.kotlin.backend.common.FileLoweringPass
import org.jetbrains.kotlin.backend.common.lower.SimpleMemberScope
import org.jetbrains.kotlin.backend.common.runOnFilePostfix
import org.jetbrains.kotlin.backend.jvm.descriptors.createValueParameter
import org.jetbrains.kotlin.backend.konan.Context
import org.jetbrains.kotlin.backend.konan.descriptors.getKonanInternalFunctions
import org.jetbrains.kotlin.backend.konan.descriptors.synthesizedName
import org.jetbrains.kotlin.backend.konan.ir.createArrayOfExpression
import org.jetbrains.kotlin.backend.konan.ir.createSimpleDelegatingConstructor
import org.jetbrains.kotlin.backend.konan.ir.createSimpleDelegatingConstructorDescriptor
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.descriptors.impl.ClassConstructorDescriptorImpl
import org.jetbrains.kotlin.descriptors.impl.ClassDescriptorImpl
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.declarations.impl.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.*
import org.jetbrains.kotlin.ir.util.dump
import org.jetbrains.kotlin.ir.util.transform
import org.jetbrains.kotlin.ir.util.transformFlat
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.resolve.descriptorUtil.module
import org.jetbrains.kotlin.types.TypeProjectionImpl
import org.jetbrains.kotlin.types.TypeSubstitutor
import org.jetbrains.kotlin.utils.addToStdlib.singletonList
import org.jetbrains.kotlin.utils.addToStdlib.singletonOrEmptyList

internal class EnumUsageLowering(val context: Context)
    : IrElementTransformerVoid(), FileLoweringPass {

    override fun lower(irFile: IrFile) {
        visitFile(irFile)
    }

    override fun visitGetEnumValue(expression: IrGetEnumValue): IrExpression {
        val enumClassDescriptor = expression.descriptor.containingDeclaration as ClassDescriptor
        return loadEnumEntry(expression.startOffset, expression.endOffset, enumClassDescriptor, expression.descriptor.name)
    }

    override fun visitGetObjectValue(expression: IrGetObjectValue): IrExpression {
        if (expression.descriptor.kind != ClassKind.ENUM_ENTRY)
            return super.visitGetObjectValue(expression)
        val enumClassDescriptor = expression.descriptor.containingDeclaration as ClassDescriptor
        return loadEnumEntry(expression.startOffset, expression.endOffset, enumClassDescriptor, expression.descriptor.name)
    }

    private fun loadEnumEntry(startOffset: Int, endOffset: Int, enumClassDescriptor: ClassDescriptor, name: Name): IrExpression {
        val loweredEnum = context.specialDescriptorsFactory.getLoweredEnum(enumClassDescriptor)
        val ordinal = loweredEnum.entriesMap[name]!!
        return IrCallImpl(startOffset, endOffset, loweredEnum.itemGetter).apply {
            dispatchReceiver = IrCallImpl(startOffset, endOffset, loweredEnum.valuesGetter)
            putValueArgument(0, IrConstImpl.int(startOffset, endOffset, enumClassDescriptor.module.builtIns.intType, ordinal))
        }
    }

    override fun visitCall(expression: IrCall): IrExpression {
        expression.transformChildrenVoid(this)
        val functionDescriptor = expression.descriptor as? FunctionDescriptor
        if (functionDescriptor == null) return expression
        if (functionDescriptor.kind != CallableMemberDescriptor.Kind.SYNTHESIZED) return expression
        val classDescriptor = functionDescriptor.containingDeclaration as? ClassDescriptor
        if (classDescriptor == null || classDescriptor.kind != ClassKind.ENUM_CLASS) return expression
        val loweredEnum = context.specialDescriptorsFactory.getLoweredEnum(classDescriptor)
        val loweredFunction = when (functionDescriptor.name.asString()) {
            "values" -> loweredEnum.valuesFunction
            "valueOf" -> loweredEnum.valueOfFunction
            else -> throw AssertionError("Unexpected synthetic enum function: $functionDescriptor")
        }
        return IrCallImpl(expression.startOffset, expression.endOffset, loweredFunction).apply {
            expression.descriptor.valueParameters.forEach { p -> putValueArgument(p, expression.getValueArgument(p)) }
        }
    }
}

internal class EnumClassLowering(val context: Context) : ClassLoweringPass {
    fun run(irFile: IrFile) {
        runOnFilePostfix(irFile)
        EnumUsageLowering(context).lower(irFile)
    }

    override fun lower(irClass: IrClass) {
        val descriptor = irClass.descriptor
        if (descriptor.kind != ClassKind.ENUM_CLASS) return
        EnumClassTransformer(irClass).run()
    }

    private interface EnumConstructorCallTransformer {
        fun transform(enumConstructorCall: IrEnumConstructorCall): IrExpression
        fun transform(delegatingConstructorCall: IrDelegatingConstructorCall): IrExpression
    }

    private inner class EnumClassTransformer(val irClass: IrClass) {
        private val loweredEnum = context.specialDescriptorsFactory.getLoweredEnum(irClass.descriptor)
        private val enumEntryOrdinals = mutableMapOf<ClassDescriptor, Int>()
        private val loweredEnumConstructors = mutableMapOf<ClassConstructorDescriptor, ClassConstructorDescriptor>()
        private val descriptorToIrConstructorWithDefaultArguments = mutableMapOf<ClassConstructorDescriptor, IrConstructor>()
        private val defaultEnumEntryConstructors = mutableMapOf<ClassConstructorDescriptor, ClassConstructorDescriptor>()
        private val loweredEnumConstructorParameters = mutableMapOf<ValueParameterDescriptor, ValueParameterDescriptor>()

        fun run() {
            insertInstanceInitializerCall()
            assignOrdinalsToEnumEntries()
            lowerEnumConstructors(irClass)
            lowerEnumEntriesClasses()
            val defaultClass = createDefaultClassForEnumEntries()
            lowerEnumClassBody()
            if (defaultClass != null)
                irClass.declarations.add(defaultClass)
            createImplObject()
        }

        private fun insertInstanceInitializerCall() {
            irClass.transformChildrenVoid(object: IrElementTransformerVoid() {
                override fun visitClass(declaration: IrClass): IrStatement {
                    // Skip nested
                    return declaration
                }

                override fun visitConstructor(declaration: IrConstructor): IrStatement {
                    declaration.transformChildrenVoid(this)

                    val blockBody = declaration.body as? IrBlockBody ?: throw AssertionError("Unexpected constructor body: ${declaration.body}")
                    if (blockBody.statements.all { it !is IrInstanceInitializerCall }) {
                        blockBody.statements.transformFlat {
                            if (it is IrEnumConstructorCall)
                                listOf(it, IrInstanceInitializerCallImpl(declaration.startOffset, declaration.startOffset,
                                        irClass.descriptor))
                            else null
                        }
                    }
                    return declaration
                }
            })
        }

        private fun assignOrdinalsToEnumEntries() {
            var ordinal = 0
            irClass.declarations.forEach {
                if (it is IrEnumEntry) {
                    enumEntryOrdinals.put(it.descriptor, ordinal)
                    ordinal++
                }
            }
        }

        private fun lowerEnumEntriesClasses() {
            irClass.declarations.transformFlat { declaration ->
                if (declaration is IrEnumEntry) {
                    declaration.singletonList() + lowerEnumEntryClass(declaration.correspondingClass).singletonOrEmptyList()
                } else null
            }
        }

        private fun lowerEnumEntryClass(enumEntryClass: IrClass?): IrClass? {
            if (enumEntryClass == null) return null

            lowerEnumConstructors(enumEntryClass)

            return enumEntryClass
        }

        private fun createDefaultClassForEnumEntries(): IrClass? {
            if (!irClass.declarations.any({ it is IrEnumEntry && it.correspondingClass == null })) return null
            val startOffset = irClass.startOffset
            val endOffset = irClass.endOffset
            val descriptor = irClass.descriptor
            val defaultClassDescriptor = ClassDescriptorImpl(descriptor, "DEFAULT".synthesizedName, Modality.FINAL,
                    ClassKind.CLASS, descriptor.defaultType.singletonList(), SourceElement.NO_SOURCE, false)
            val defaultClass = IrClassImpl(startOffset, endOffset, IrDeclarationOrigin.DEFINED, defaultClassDescriptor)

            val constructors = mutableSetOf<ClassConstructorDescriptor>()
            var primaryConstructor: ClassConstructorDescriptor? = null

            descriptor.constructors.forEach {
                val loweredEnumConstructor = loweredEnumConstructors[it]!!
                val constructorDescriptor = defaultClassDescriptor.createSimpleDelegatingConstructorDescriptor(loweredEnumConstructor)
                val constructor = defaultClassDescriptor.createSimpleDelegatingConstructor(loweredEnumConstructor, constructorDescriptor)
                constructors.add(constructorDescriptor)
                defaultClass.declarations.add(constructor)
                defaultEnumEntryConstructors.put(loweredEnumConstructor, constructorDescriptor)
                if (loweredEnumConstructor.isPrimary)
                    primaryConstructor = constructorDescriptor

                val irConstructor = descriptorToIrConstructorWithDefaultArguments[loweredEnumConstructor]
                if (irConstructor != null) {
                    it.valueParameters.filter { it.declaresDefaultValue() }.forEach { argument ->
                        val loweredArgument = loweredEnumConstructor.valueParameters[argument.loweredIndex()]
                        val body = irConstructor.getDefault(loweredArgument)!!
                        body.transformChildrenVoid(ParameterMapper(constructorDescriptor))
                        constructor.putDefault(constructorDescriptor.valueParameters[loweredArgument.index], body)
                    }
                }
            }

            val memberScope = SimpleMemberScope(irClass.descriptor.unsubstitutedMemberScope.getContributedDescriptors().toList())
            defaultClassDescriptor.initialize(memberScope, constructors, primaryConstructor)

            return defaultClass
        }

        private fun createImplObject() {
            val startOffset = irClass.startOffset
            val endOffset = irClass.endOffset
            val implObjectDescriptor = loweredEnum.implObjectDescriptor
            val implObject = IrClassImpl(startOffset, endOffset, IrDeclarationOrigin.DEFINED, implObjectDescriptor)

            val enumEntries = mutableListOf<IrEnumEntry>()
            var i = 0
            while (i < irClass.declarations.size) {
                val declaration = irClass.declarations[i]
                var delete = false
                when (declaration) {
                    is IrEnumEntry -> {
                        enumEntries.add(declaration)
                        delete = true
                    }
                    is IrFunction -> {
                        if (declaration.body is IrSyntheticBody)
                            delete = true
                    }
                }
                if (delete)
                    irClass.declarations.removeAt(i)
                else
                    ++i
            }

            val constructorOfAny = irClass.descriptor.module.builtIns.any.constructors.first()
            val constructor = implObjectDescriptor.createSimpleDelegatingConstructor(constructorOfAny, implObjectDescriptor.constructors.single())

            implObject.declarations.add(constructor)
            implObject.declarations.add(createSyntheticValuesPropertyDeclaration(enumEntries))
            implObject.declarations.add(createSyntheticValuesMethodDeclaration())
            implObject.declarations.add(createSyntheticValueOfMethodDeclaration())

            irClass.declarations.add(implObject)
        }

        private fun createSyntheticValuesPropertyDeclaration(enumEntries: List<IrEnumEntry>): IrPropertyImpl {
            val irValuesInitializer = context.createArrayOfExpression(irClass.descriptor.defaultType,
                    enumEntries.sortedBy { it.descriptor.name }.map { it.initializerExpression })

            val startOffset = irClass.startOffset
            val endOffset = irClass.endOffset
            val irField = IrFieldImpl(startOffset, endOffset, DECLARATION_ORIGIN_ENUM,
                    loweredEnum.valuesProperty,
                    IrExpressionBodyImpl(startOffset, endOffset, irValuesInitializer))

            val getter = IrFunctionImpl(startOffset, endOffset, DECLARATION_ORIGIN_ENUM, loweredEnum.valuesGetter)

            val receiver = IrGetObjectValueImpl(startOffset, endOffset,
                    loweredEnum.implObjectDescriptor.defaultType, loweredEnum.implObjectDescriptor)
            val value = IrGetFieldImpl(startOffset, endOffset, loweredEnum.valuesProperty, receiver)
            val returnStatement = IrReturnImpl(startOffset, endOffset,
                    loweredEnum.valuesGetter.returnType!!, loweredEnum.valuesGetter, value)
            getter.body = IrBlockBodyImpl(startOffset, endOffset, listOf(returnStatement))

            val irProperty = IrPropertyImpl(startOffset, endOffset, DECLARATION_ORIGIN_ENUM,
                    false, loweredEnum.valuesProperty, irField, getter, null)
            return irProperty
        }

        private object DECLARATION_ORIGIN_ENUM :
                IrDeclarationOriginImpl("ENUM")

        private val genericValueOfFun = context.builtIns.getKonanInternalFunctions("valueOfForEnum").single()

        private val genericValuesFun = context.builtIns.getKonanInternalFunctions("valuesForEnum").single()

        private fun createSyntheticValuesMethodDeclaration(): IrFunction {
            val startOffset = irClass.startOffset
            val endOffset = irClass.endOffset
            val typeParameterT = genericValuesFun.typeParameters[0]
            val enumClassType = irClass.descriptor.defaultType
            val typeSubstitutor = TypeSubstitutor.create(mapOf(typeParameterT.typeConstructor to TypeProjectionImpl(enumClassType)))
            val substitutedValueOf = genericValuesFun.substitute(typeSubstitutor)!!

            val irValuesCall = IrCallImpl(startOffset, endOffset, substitutedValueOf, mapOf(typeParameterT to enumClassType))
                    .apply {
                        val receiver = IrGetObjectValueImpl(startOffset, endOffset,
                                loweredEnum.implObjectDescriptor.defaultType, loweredEnum.implObjectDescriptor)
                        putValueArgument(0, IrGetFieldImpl(startOffset, endOffset, loweredEnum.valuesProperty, receiver))
                    }

            val body = IrBlockBodyImpl(
                    startOffset, endOffset,
                    listOf(IrReturnImpl(startOffset, endOffset, loweredEnum.valuesFunction, irValuesCall))
            )
            return IrFunctionImpl(startOffset, endOffset, DECLARATION_ORIGIN_ENUM, loweredEnum.valuesFunction, body)
        }

        private fun createSyntheticValueOfMethodDeclaration(): IrFunction {
            val startOffset = irClass.startOffset
            val endOffset = irClass.endOffset
            val typeParameterT = genericValueOfFun.typeParameters[0]
            val enumClassType = irClass.descriptor.defaultType
            val typeSubstitutor = TypeSubstitutor.create(mapOf(typeParameterT.typeConstructor to TypeProjectionImpl(enumClassType)))
            val substitutedValueOf = genericValueOfFun.substitute(typeSubstitutor)!!

            val irValueOfCall = IrCallImpl(startOffset, endOffset, substitutedValueOf, mapOf(typeParameterT to enumClassType))
                    .apply {
                        putValueArgument(0, IrGetValueImpl(startOffset, endOffset, loweredEnum.valueOfFunction.valueParameters[0]))
                        val receiver = IrGetObjectValueImpl(startOffset, endOffset,
                                loweredEnum.implObjectDescriptor.defaultType, loweredEnum.implObjectDescriptor)
                        putValueArgument(1, IrGetFieldImpl(startOffset, endOffset, loweredEnum.valuesProperty, receiver))
                    }

            val body = IrBlockBodyImpl(
                    startOffset, endOffset,
                    listOf(IrReturnImpl(startOffset, endOffset, loweredEnum.valueOfFunction, irValueOfCall))
            )
            return IrFunctionImpl(startOffset, endOffset, DECLARATION_ORIGIN_ENUM, loweredEnum.valueOfFunction, body)
        }

        private fun lowerEnumConstructors(irClass: IrClass) {
            irClass.declarations.transform { declaration ->
                if (declaration is IrConstructor)
                    transformEnumConstructor(declaration)
                else
                    declaration
            }
        }

        private fun transformEnumConstructor(enumConstructor: IrConstructor): IrConstructor {
            val constructorDescriptor = enumConstructor.descriptor
            val loweredConstructorDescriptor = lowerEnumConstructor(constructorDescriptor)
            val loweredEnumConstructor = IrConstructorImpl(
                    enumConstructor.startOffset, enumConstructor.endOffset, enumConstructor.origin,
                    loweredConstructorDescriptor,
                    enumConstructor.body!! // will be transformed later
            )
            enumConstructor.descriptor.valueParameters.filter { it.declaresDefaultValue() }.forEach {
                val body = enumConstructor.getDefault(it)!!
                body.transformChildrenVoid(ParameterMapper(constructorDescriptor))
                loweredEnumConstructor.putDefault(loweredConstructorDescriptor.valueParameters[it.loweredIndex()], body)
                descriptorToIrConstructorWithDefaultArguments[loweredConstructorDescriptor] = loweredEnumConstructor
            }
            return loweredEnumConstructor
        }

        private fun lowerEnumConstructor(constructorDescriptor: ClassConstructorDescriptor): ClassConstructorDescriptor {
            val loweredConstructorDescriptor = ClassConstructorDescriptorImpl.createSynthesized(
                    constructorDescriptor.containingDeclaration,
                    constructorDescriptor.annotations,
                    constructorDescriptor.isPrimary,
                    constructorDescriptor.source
            )

            val valueParameters =
                    listOf(
                            loweredConstructorDescriptor.createValueParameter(0, "name", context.builtIns.stringType),
                            loweredConstructorDescriptor.createValueParameter(1, "ordinal", context.builtIns.intType)
                    ) +
                            constructorDescriptor.valueParameters.map {
                                lowerConstructorValueParameter(loweredConstructorDescriptor, it)
                            }
            loweredConstructorDescriptor.initialize(valueParameters, Visibilities.PROTECTED)

            loweredConstructorDescriptor.returnType = constructorDescriptor.returnType

            loweredEnumConstructors[constructorDescriptor] = loweredConstructorDescriptor

            return loweredConstructorDescriptor
        }

        private fun lowerConstructorValueParameter(
                loweredConstructorDescriptor: ClassConstructorDescriptor,
                valueParameterDescriptor: ValueParameterDescriptor
        ): ValueParameterDescriptor {
            val loweredValueParameterDescriptor = valueParameterDescriptor.copy(
                    loweredConstructorDescriptor,
                    valueParameterDescriptor.name,
                    valueParameterDescriptor.loweredIndex()
            )
            loweredEnumConstructorParameters[valueParameterDescriptor] = loweredValueParameterDescriptor
            return loweredValueParameterDescriptor
        }

        private fun lowerEnumClassBody() {
            irClass.transformChildrenVoid(EnumClassBodyTransformer())
        }

        private inner class InEnumClassConstructor(val enumClassConstructor: ClassConstructorDescriptor) :
                EnumConstructorCallTransformer {
            override fun transform(enumConstructorCall: IrEnumConstructorCall): IrExpression {
                val startOffset = enumConstructorCall.startOffset
                val endOffset = enumConstructorCall.endOffset
                val origin = enumConstructorCall.origin

                val result = IrDelegatingConstructorCallImpl(startOffset, endOffset, enumConstructorCall.descriptor)

                assert(result.descriptor.valueParameters.size == 2) {
                    "Enum(String, Int) constructor call expected:\n${result.dump()}"
                }

                val nameParameter = enumClassConstructor.valueParameters.getOrElse(0) {
                    throw AssertionError("No 'name' parameter in enum constructor: $enumClassConstructor")
                }

                val ordinalParameter = enumClassConstructor.valueParameters.getOrElse(1) {
                    throw AssertionError("No 'ordinal' parameter in enum constructor: $enumClassConstructor")
                }

                result.putValueArgument(0, IrGetValueImpl(startOffset, endOffset, nameParameter, origin))
                result.putValueArgument(1, IrGetValueImpl(startOffset, endOffset, ordinalParameter, origin))

                return result
            }

            override fun transform(delegatingConstructorCall: IrDelegatingConstructorCall): IrExpression {
                val descriptor = delegatingConstructorCall.descriptor
                val startOffset = delegatingConstructorCall.startOffset
                val endOffset = delegatingConstructorCall.endOffset

                val loweredDelegatedConstructor = loweredEnumConstructors.getOrElse(descriptor) {
                    throw AssertionError("Constructor called in enum entry initializer should've been lowered: $descriptor")
                }

                val result = IrDelegatingConstructorCallImpl(startOffset, endOffset, loweredDelegatedConstructor)

                result.putValueArgument(0, IrGetValueImpl(startOffset, endOffset, enumClassConstructor.valueParameters[0]))
                result.putValueArgument(1, IrGetValueImpl(startOffset, endOffset, enumClassConstructor.valueParameters[1]))

                descriptor.valueParameters.forEach { valueParameter ->
                    result.putValueArgument(valueParameter.loweredIndex(), delegatingConstructorCall.getValueArgument(valueParameter))
                }

                return result
            }
        }

        private abstract inner class InEnumEntry(private val enumEntry: ClassDescriptor) : EnumConstructorCallTransformer {
            override fun transform(enumConstructorCall: IrEnumConstructorCall): IrExpression {
                val name = enumEntry.name.asString()
                val ordinal = enumEntryOrdinals[enumEntry]!!

                val descriptor = enumConstructorCall.descriptor
                val startOffset = enumConstructorCall.startOffset
                val endOffset = enumConstructorCall.endOffset

                val loweredConstructor = loweredEnumConstructors.getOrElse(descriptor) {
                    throw AssertionError("Constructor called in enum entry initializer should've been lowered: $descriptor")
                }

                val result = createConstructorCall(startOffset, endOffset, loweredConstructor)

                result.putValueArgument(0, IrConstImpl.string(startOffset, endOffset, context.builtIns.stringType, name))
                result.putValueArgument(1, IrConstImpl.int(startOffset, endOffset, context.builtIns.intType, ordinal))

                descriptor.valueParameters.forEach { valueParameter ->
                    val i = valueParameter.index
                    result.putValueArgument(i + 2, enumConstructorCall.getValueArgument(i))
                }

                return result
            }

            override fun transform(delegatingConstructorCall: IrDelegatingConstructorCall): IrExpression {
                throw AssertionError("Unexpected delegating constructor call within enum entry: $enumEntry")
            }

            abstract fun createConstructorCall(startOffset: Int, endOffset: Int, loweredConstructor: ClassConstructorDescriptor): IrMemberAccessExpression
        }

        private inner class InEnumEntryClassConstructor(enumEntry: ClassDescriptor) : InEnumEntry(enumEntry) {
            override fun createConstructorCall(startOffset: Int, endOffset: Int, loweredConstructor: ClassConstructorDescriptor)
                    = IrDelegatingConstructorCallImpl(startOffset, endOffset, loweredConstructor)
        }

        private inner class InEnumEntryInitializer(enumEntry: ClassDescriptor) : InEnumEntry(enumEntry) {
            override fun createConstructorCall(startOffset: Int, endOffset: Int, loweredConstructor: ClassConstructorDescriptor)
                    = IrCallImpl(startOffset, endOffset, defaultEnumEntryConstructors[loweredConstructor] ?: loweredConstructor)
        }

        private inner class EnumClassBodyTransformer : IrElementTransformerVoid() {
            private var enumConstructorCallTransformer: EnumConstructorCallTransformer? = null

            override fun visitEnumEntry(declaration: IrEnumEntry): IrStatement {
                assert(enumConstructorCallTransformer == null) { "Nested enum entry initialization:\n${declaration.dump()}" }

                enumConstructorCallTransformer = InEnumEntryInitializer(declaration.descriptor)

                var result: IrEnumEntry = IrEnumEntryImpl(declaration.startOffset, declaration.endOffset, declaration.origin,
                        declaration.descriptor, null, declaration.initializerExpression)
                result = super.visitEnumEntry(result) as IrEnumEntry

                enumConstructorCallTransformer = null

                return result
            }

            override fun visitConstructor(declaration: IrConstructor): IrStatement {
                val constructorDescriptor = declaration.descriptor
                val containingClass = constructorDescriptor.containingDeclaration

                // TODO local (non-enum) class in enum class constructor?
                val previous = enumConstructorCallTransformer

                if (containingClass.kind == ClassKind.ENUM_ENTRY) {
                    assert(enumConstructorCallTransformer == null) { "Nested enum entry initialization:\n${declaration.dump()}" }
                    enumConstructorCallTransformer = InEnumEntryClassConstructor(containingClass)
                } else if (containingClass.kind == ClassKind.ENUM_CLASS) {
                    assert(enumConstructorCallTransformer == null) { "Nested enum entry initialization:\n${declaration.dump()}" }
                    enumConstructorCallTransformer = InEnumClassConstructor(constructorDescriptor)
                }

                val result = super.visitConstructor(declaration)

                enumConstructorCallTransformer = previous

                return result
            }

            override fun visitEnumConstructorCall(expression: IrEnumConstructorCall): IrExpression {
                expression.transformChildrenVoid(this)

                val callTransformer = enumConstructorCallTransformer ?:
                        throw AssertionError("Enum constructor call outside of enum entry initialization or enum class constructor:\n" + irClass.dump())


                return callTransformer.transform(expression)
            }

            override fun visitDelegatingConstructorCall(expression: IrDelegatingConstructorCall): IrExpression {
                expression.transformChildrenVoid(this)

                if (expression.descriptor.containingDeclaration.kind == ClassKind.ENUM_CLASS) {
                    val callTransformer = enumConstructorCallTransformer ?:
                            throw AssertionError("Enum constructor call outside of enum entry initialization or enum class constructor:\n" + irClass.dump())

                    return callTransformer.transform(expression)
                }
                return expression
            }

            override fun visitGetValue(expression: IrGetValue): IrExpression {
                val loweredParameter = loweredEnumConstructorParameters[expression.descriptor]
                if (loweredParameter != null)
                    return IrGetValueImpl(expression.startOffset, expression.endOffset, loweredParameter, expression.origin)
                else
                    return expression
            }
        }
    }
}

private fun ValueParameterDescriptor.loweredIndex(): Int = index + 2

private class ParameterMapper(val originalDescriptor: FunctionDescriptor) : IrElementTransformerVoid() {
    override fun visitGetValue(expression: IrGetValue): IrExpression {
        val descriptor = expression.descriptor
        when (descriptor) {
            is ValueParameterDescriptor -> {
                return IrGetValueImpl(expression.startOffset,
                        expression.endOffset,
                        originalDescriptor.valueParameters[descriptor.index])
            }
        }
        return expression
    }
}
