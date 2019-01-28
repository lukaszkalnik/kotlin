/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.types

import org.jetbrains.kotlin.types.AbstractTypeChecker.doIsSubTypeOf
import org.jetbrains.kotlin.types.AbstractTypeCheckerContext.LowerCapturedTypePolicy.*
import org.jetbrains.kotlin.types.AbstractTypeCheckerContext.SeveralSupertypesWithSameConstructorPolicy
import org.jetbrains.kotlin.types.AbstractTypeCheckerContext.SupertypesPolicy
import org.jetbrains.kotlin.types.model.*
import org.jetbrains.kotlin.utils.SmartList
import org.jetbrains.kotlin.utils.SmartSet
import java.util.*


abstract class AbstractTypeCheckerContext : TypeSystemContext {


    abstract fun substitutionSupertypePolicy(type: SimpleTypeIM): SupertypesPolicy.DoCustomTransform

    abstract fun areEqualTypeConstructors(a: TypeConstructorIM, b: TypeConstructorIM): Boolean

    abstract fun intersectTypes(projections: List<KotlinTypeIM>): KotlinTypeIM

    abstract fun nullabilityIsPossibleSupertype(subType: SimpleTypeIM, superType: SimpleTypeIM): Boolean

    /**
     * Gets called by [AbstractTypeChecker] as entry of isSubTypeOf, it is suggested to call [AbstractTypeChecker.doIsSubTypeOf]
     */
    open fun enterIsSubTypeOf(subType: KotlinTypeIM, superType: KotlinTypeIM): Boolean {
        return doIsSubTypeOf(subType, superType)
    }

    abstract val isErrorTypeEqualsToAnything: Boolean

    protected var argumentsDepth = 0


    internal inline fun <T> runWithArgumentsSettings(subArgument: KotlinTypeIM, f: AbstractTypeCheckerContext.() -> T): T {
        if (argumentsDepth > 100) {
            error("Arguments depth is too high. Some related argument: $subArgument")
        }

        argumentsDepth++
        val result = f()
        argumentsDepth--
        return result
    }

    open fun getLowerCapturedTypePolicy(subType: SimpleTypeIM, superType: CapturedTypeIM): LowerCapturedTypePolicy = CHECK_SUBTYPE_AND_LOWER
    open fun addSubtypeConstraint(subType: KotlinTypeIM, superType: KotlinTypeIM): Boolean? = null
    open val sameConstructorPolicy get() = SeveralSupertypesWithSameConstructorPolicy.INTERSECT_ARGUMENTS_AND_CHECK_AGAIN

    enum class SeveralSupertypesWithSameConstructorPolicy {
        TAKE_FIRST_FOR_SUBTYPING,
        FORCE_NOT_SUBTYPE,
        CHECK_ANY_OF_THEM,
        INTERSECT_ARGUMENTS_AND_CHECK_AGAIN
    }

    enum class LowerCapturedTypePolicy {
        CHECK_ONLY_LOWER,
        CHECK_SUBTYPE_AND_LOWER,
        SKIP_LOWER
    }

    private var supertypesLocked = false

    var supertypesDeque: ArrayDeque<SimpleTypeIM>? = null
        private set
    var supertypesSet: MutableSet<SimpleTypeIM>? = null
        private set


    fun initialize() {
        assert(!supertypesLocked)
        supertypesLocked = true

        if (supertypesDeque == null) {
            supertypesDeque = ArrayDeque(4)
        }
        if (supertypesSet == null) {
            supertypesSet = SmartSet.create()
        }
    }

    fun clear() {
        supertypesDeque!!.clear()
        supertypesSet!!.clear()
        supertypesLocked = false
    }

    inline fun anySupertype(
        start: SimpleTypeIM,
        predicate: (SimpleTypeIM) -> Boolean,
        supertypesPolicy: (SimpleTypeIM) -> SupertypesPolicy
    ): Boolean {
        if (predicate(start)) return true

        initialize()

        val deque = supertypesDeque!!
        val visitedSupertypes = supertypesSet!!

        deque.push(start)
        while (deque.isNotEmpty()) {
            if (visitedSupertypes.size > 1000) {
                error("Too many supertypes for type: $start. Supertypes = ${visitedSupertypes.joinToString()}")
            }
            val current = deque.pop()
            if (!visitedSupertypes.add(current)) continue

            val policy = supertypesPolicy(current).takeIf { it != SupertypesPolicy.None } ?: continue
            for (supertype in current.typeConstructor().supertypes()) {
                val newType = policy.transformType(this, supertype)
                if (predicate(newType)) {
                    clear()
                    return true
                }
                deque.add(newType)
            }
        }

        clear()
        return false
    }

    sealed class SupertypesPolicy {
        abstract fun transformType(context: AbstractTypeCheckerContext, type: KotlinTypeIM): SimpleTypeIM

        object None : SupertypesPolicy() {
            override fun transformType(context: AbstractTypeCheckerContext, type: KotlinTypeIM) =
                throw UnsupportedOperationException("Should not be called")
        }

        object UpperIfFlexible : SupertypesPolicy() {
            override fun transformType(context: AbstractTypeCheckerContext, type: KotlinTypeIM) =
                with(context) { type.upperBoundIfFlexible() }
        }

        object LowerIfFlexible : SupertypesPolicy() {
            override fun transformType(context: AbstractTypeCheckerContext, type: KotlinTypeIM) =
                with(context) { type.lowerBoundIfFlexible() }
        }

        abstract class DoCustomTransform : SupertypesPolicy()
    }
}

object AbstractTypeChecker {
    fun isSubtypeOf(context: AbstractTypeCheckerContext, subType: KotlinTypeIM, superType: KotlinTypeIM): Boolean = with(context) {
        return context.enterIsSubTypeOf(subType, superType)
    }

    fun equalTypes(context: AbstractTypeCheckerContext, a: KotlinTypeIM, b: KotlinTypeIM): Boolean = with(context) {
        if (a === b) return true

        if (isCommonDenotableType(a) && isCommonDenotableType(b)) {
            val simpleA = a.lowerBoundIfFlexible() //TODO: Delegate ??
            if (!areEqualTypeConstructors(a.typeConstructor(), b.typeConstructor())) return false
            if (simpleA.argumentsCount() == 0) {
                if (a.hasFlexibleNullability() || b.hasFlexibleNullability()) return true

                return simpleA.isMarkedNullable() == b.lowerBoundIfFlexible().isMarkedNullable()
            }
        }

        return isSubtypeOf(context, a, b) && isSubtypeOf(context, b, a)
    }


    fun AbstractTypeCheckerContext.doIsSubTypeOf(subType: KotlinTypeIM, superType: KotlinTypeIM): Boolean {
        checkSubtypeForSpecialCases(subType.lowerBoundIfFlexible(), superType.upperBoundIfFlexible())?.let {
            addSubtypeConstraint(subType, superType)
            return it
        }

        // we should add constraints with flexible types, otherwise we never get flexible type as answer in constraint system
        addSubtypeConstraint(subType, superType)?.let { return it }

        return isSubtypeOfForSingleClassifierType(subType.lowerBoundIfFlexible(), superType.upperBoundIfFlexible())
    }

    private fun AbstractTypeCheckerContext.hasNothingSupertype(type: SimpleTypeIM) = // todo add tests
        anySupertype(type, { it.typeConstructor().isNothingConstructor() }) {
            if (it.isClassType()) {
                SupertypesPolicy.None
            } else {
                SupertypesPolicy.LowerIfFlexible
            }
        }

    private fun AbstractTypeCheckerContext.isSubtypeOfForSingleClassifierType(subType: SimpleTypeIM, superType: SimpleTypeIM): Boolean {
        // TODO: fix assertions
//        assert(subType.isSingleClassifierType || subType.typeConstructor().isIntersection() || subType.isAllowedTypeVariable) {
//            "Not singleClassifierType and not intersection subType: $subType"
//        }
//        assert(superType.isSingleClassifierType || superType.isAllowedTypeVariable) {
//            "Not singleClassifierType superType: $superType"
//        }

        // TODO: port NullabilityChecker
        if (!nullabilityIsPossibleSupertype(subType, superType)) return false

        val superConstructor = superType.typeConstructor()

        if (isEqualTypeConstructors(subType.typeConstructor(), superConstructor) && superConstructor.parametersCount() == 0) return true
        if (superType.typeConstructor().isAnyConstructor()) return true

        val supertypesWithSameConstructor = findCorrespondingSupertypes(subType, superConstructor)
        when (supertypesWithSameConstructor.size) {
            0 -> return hasNothingSupertype(subType) // todo Nothing & Array<Number> <: Array<String>
            1 -> return isSubtypeForSameConstructor(supertypesWithSameConstructor.first().asArgumentList(), superType)

            else -> { // at least 2 supertypes with same constructors. Such case is rare
                when (sameConstructorPolicy) {
                    SeveralSupertypesWithSameConstructorPolicy.FORCE_NOT_SUBTYPE -> return false
                    SeveralSupertypesWithSameConstructorPolicy.TAKE_FIRST_FOR_SUBTYPING -> return isSubtypeForSameConstructor(
                        supertypesWithSameConstructor.first().asArgumentList(),
                        superType
                    )

                    SeveralSupertypesWithSameConstructorPolicy.CHECK_ANY_OF_THEM,
                    SeveralSupertypesWithSameConstructorPolicy.INTERSECT_ARGUMENTS_AND_CHECK_AGAIN ->
                        if (supertypesWithSameConstructor.any { isSubtypeForSameConstructor(it.asArgumentList(), superType) }) return true
                }

                if (sameConstructorPolicy != SeveralSupertypesWithSameConstructorPolicy.INTERSECT_ARGUMENTS_AND_CHECK_AGAIN) return false


                val newArguments = ArgumentList()
                for (index in 0 until superConstructor.parametersCount()) {
                    val allProjections = supertypesWithSameConstructor.map {
                        it.getArgumentOrNull(index)?.takeIf { it.getVariance() == TypeVariance.INV }?.getType()
                            ?: error("Incorrect type: $it, subType: $subType, superType: $superType")
                    }

                    // todo discuss
                    val intersection = intersectTypes(allProjections).asTypeArgument()
                    newArguments.add(intersection)
                }

                return isSubtypeForSameConstructor(newArguments, superType)
            }
        }
    }

    fun AbstractTypeCheckerContext.isSubtypeForSameConstructor(
        capturedSubArguments: TypeArgumentListIM,
        superType: SimpleTypeIM
    ): Boolean {
        // No way to check, as no index sometimes
        //if (capturedSubArguments === superType.arguments) return true

        //val parameters = superType.constructor.parameters
        val superTypeConstructor = superType.typeConstructor()
        for (index in 0 until superTypeConstructor.parametersCount()) {
            val superProjection = superType.getArgument(index) // todo error index
            if (superProjection.isStarProjection()) continue // A<B> <: A<*>

            val superArgumentType = superProjection.getType()
            val subArgumentType = capturedSubArguments[index].let {
                assert(it.getVariance() == TypeVariance.INV) { "Incorrect sub argument: $it" }
                it.getType()
            }

            val variance = effectiveVariance(superTypeConstructor.getParameter(index).getVariance(), superProjection.getVariance())
                ?: return isErrorTypeEqualsToAnything // todo exception?

            val correctArgument = runWithArgumentsSettings(subArgumentType) {
                when (variance) {
                    TypeVariance.INV -> equalTypes(this, subArgumentType, superArgumentType)
                    TypeVariance.OUT -> isSubtypeOf(this, subArgumentType, superArgumentType)
                    TypeVariance.IN -> isSubtypeOf(this, superArgumentType, subArgumentType)
                }
            }
            if (!correctArgument) return false
        }
        return true
    }

    private fun AbstractTypeCheckerContext.isCommonDenotableType(type: KotlinTypeIM): Boolean =
        type.typeConstructor().isDenotable() &&
                !type.isDynamic() && !type.isDefinitelyNotNullType() &&
                type.lowerBoundIfFlexible().typeConstructor() == type.upperBoundIfFlexible().typeConstructor()

    private fun effectiveVariance(declared: TypeVariance, useSite: TypeVariance): TypeVariance? {
        if (declared == TypeVariance.INV) return useSite
        if (useSite == TypeVariance.INV) return declared

        // both not INVARIANT
        if (declared == useSite) return declared

        // composite In with Out
        return null
    }

    private fun AbstractTypeCheckerContext.checkSubtypeForSpecialCases(subType: SimpleTypeIM, superType: SimpleTypeIM): Boolean? {
        if (subType.isError() || superType.isError()) {
            if (isErrorTypeEqualsToAnything) return true

            if (subType.isMarkedNullable() && !superType.isMarkedNullable()) return false

            return AbstractStrictEqualityTypeChecker.strictEqualTypes(
                this,
                subType.withNullability(false),
                superType.withNullability(false)
            )
        }

        if (subType.isStubType() || superType.isStubType()) return true


        val superTypeCaptured = superType.asCapturedType()
        val lowerType = superTypeCaptured?.lowerType()
        if (superTypeCaptured != null && lowerType != null) {
            when (getLowerCapturedTypePolicy(subType, superTypeCaptured)) {
                CHECK_ONLY_LOWER -> return isSubtypeOf(this, subType, lowerType)
                CHECK_SUBTYPE_AND_LOWER -> if (isSubtypeOf(this, subType, lowerType)) return true
                SKIP_LOWER -> Unit /*do nothing*/
            }
        }

        val superTypeConstructor = superType.typeConstructor()
        if (superTypeConstructor.isIntersection()) {
            assert(!superType.isMarkedNullable()) { "Intersection type should not be marked nullable!: $superType" }

            return superTypeConstructor.supertypes().all { isSubtypeOf(this, subType, it) }
        }

        return null
    }


    private inline fun TypeArgumentListIM.all(
        context: AbstractTypeCheckerContext,
        crossinline predicate: (TypeArgumentIM) -> Boolean
    ): Boolean = with(context) {
        repeat(size()) { index ->
            if (!predicate(get(index))) return false
        }
        return true
    }


    private fun AbstractTypeCheckerContext.collectAllSupertypesWithGivenTypeConstructor(
        baseType: SimpleTypeIM,
        constructor: TypeConstructorIM
    ): List<SimpleTypeIM> {
        if (constructor.isCommonFinalClassConstructor()) {
            return if (areEqualTypeConstructors(baseType.typeConstructor(), constructor))
                listOf(captureFromArguments(baseType, CaptureStatus.FOR_SUBTYPING) ?: baseType)
            else
                emptyList()
        }

        val result: MutableList<SimpleTypeIM> = SmartList()

        anySupertype(baseType, { false }) {
            //kotlin.require(it is SimpleType)
            val current = captureFromArguments(it, CaptureStatus.FOR_SUBTYPING) ?: it

            when {
                areEqualTypeConstructors(current.typeConstructor(), constructor) -> {
                    result.add(current)
                    SupertypesPolicy.None
                }
                current.argumentsCount() == 0 -> {
                    SupertypesPolicy.LowerIfFlexible
                }
                else -> {
                    substitutionSupertypePolicy(current)
                }
            }
        }

        return result
    }

    private fun AbstractTypeCheckerContext.collectAndFilter(classType: SimpleTypeIM, constructor: TypeConstructorIM) =
        selectOnlyPureKotlinSupertypes(collectAllSupertypesWithGivenTypeConstructor(classType, constructor))


    /**
     * If we have several paths to some interface, we should prefer pure kotlin path.
     * Example:
     *
     * class MyList : AbstractList<String>(), MutableList<String>
     *
     * We should see `String` in `get` function and others, also MyList is not subtype of MutableList<String?>
     *
     * More tests: javaAndKotlinSuperType & purelyImplementedCollection folder
     */
    private fun AbstractTypeCheckerContext.selectOnlyPureKotlinSupertypes(supertypes: List<SimpleTypeIM>): List<SimpleTypeIM> {
        if (supertypes.size < 2) return supertypes

        val allPureSupertypes = supertypes.filter {
            it.asArgumentList().all(this) { it.getType().asFlexibleType() == null }
        }
        return if (allPureSupertypes.isNotEmpty()) allPureSupertypes else supertypes
    }


    // nullability was checked earlier via nullabilityChecker
    // should be used only if you really sure that it is correct
    fun AbstractTypeCheckerContext.findCorrespondingSupertypes(
        baseType: SimpleTypeIM,
        constructor: TypeConstructorIM
    ): List<SimpleTypeIM> {
        if (baseType.isClassType()) {
            return collectAndFilter(baseType, constructor)
        }

        // i.e. superType is not a classType
        if (!constructor.isClassTypeConstructor()) {
            return collectAllSupertypesWithGivenTypeConstructor(baseType, constructor)
        }

        // todo add tests
        val classTypeSupertypes = SmartList<SimpleTypeIM>()
        anySupertype(baseType, { false }) {
            if (it.isClassType()) {
                classTypeSupertypes.add(it)
                SupertypesPolicy.None
            } else {
                SupertypesPolicy.LowerIfFlexible
            }
        }

        return classTypeSupertypes.flatMap { collectAndFilter(it, constructor) }
    }
}