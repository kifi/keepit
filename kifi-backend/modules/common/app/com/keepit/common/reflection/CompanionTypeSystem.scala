package com.keepit.common.reflection

import scala.reflect.runtime.universe._
import scala.util.{Failure, Success, Try}

object CompanionTypeSystem {

  def apply[SealedClass: TypeTag, Companion: TypeTag](fBoundedType: String): Set[Companion] = {
    val sealedClassType = typeOf[SealedClass]
    val companionType = typeOf[Companion]
    val upperBound = sealedClassType
    val refineCompanionWithTypeParameter = getTypeWithTypeParameterOrElseCheckTypeMember(companionType, fBoundedType, upperBound)
    val refineSealedClassWithTypeParameter = getTypeWithTypeParameterOrElseCheckTypeMember(sealedClassType, fBoundedType, upperBound)

    getSubclasses(sealedClassType).map { subclass =>
      val subclassType = subclass.toType

      refineSealedClassWithTypeParameter match {
        case Some(refineSealedClassTypeWith) =>
          val expectedSubclassType = refineSealedClassTypeWith(subclassType)
          require(subclassType <:< expectedSubclassType, s"Class $subclassType must extend $expectedSubclassType")
        case None =>
          require(subclassType <:< sealedClassType, s"Class $subclassType must extend $sealedClassType")
          checkTypeMember(subclassType, fBoundedType, subclassType)
      }

      val subclassCompanionType = subclass.companionSymbol.typeSignature

      refineCompanionWithTypeParameter match {
        case Some(refineCompanionTypeWith) =>
          val expectedSubclassCompanionType = refineCompanionTypeWith(subclassType)
          require(subclassCompanionType <:< expectedSubclassCompanionType, s"Companion object $subclassCompanionType of $subclass must extend $expectedSubclassCompanionType")
        case None =>
          require(subclassCompanionType <:< companionType, s"Companion object $subclassCompanionType of $subclass must extend $companionType")
          checkTypeMember(subclassCompanionType, fBoundedType, subclassType)
      }
      getCompanion(subclass).asInstanceOf[Companion]
   }
  }

  private def getTypeWithTypeParameterOrElseCheckTypeMember(owner: Type, fBoundedType: TypeName, upperBound: Type): Option[Type => Type] = {
    Try { refineExistentialTypeParameter(owner, fBoundedType, upperBound) } match {
      case Success(typeWithTypeParameter) => Some(typeWithTypeParameter)
      case Failure(fBoundedTypeParameterException) => {
        Try(checkExistentialTypeMember(owner, fBoundedType, upperBound)) match {
          case Success(_) => None
          case Failure(fBoundedTypeMemberException) =>
            throw new IllegalArgumentException(s"Could not find a valid F-bounded type $fBoundedType in $owner: \n No valid type parameter (${fBoundedTypeParameterException.getMessage}) \n No valid type member (${fBoundedTypeMemberException.getMessage})")
        }
      }
    }
  }

  private def getCompanion(clazz: ClassSymbol): Any = {
    val m = runtimeMirror(getClass.getClassLoader)
    m.reflectModule(clazz.companionSymbol.asModule).instance
  }

  private def getSubclasses(classType: Type): Set[ClassSymbol] = {
    val clazz = classType.typeSymbol.asClass
    require(clazz.isSealed, s"Class $clazz of type $classType must be sealed.")
    clazz.knownDirectSubclasses.map(_.asClass)
  }

   private def checkTypeMember(owner: Type, name: TypeName, expectedType: Type): Unit = {
     val typeMemberType = owner.member(name).typeSignature
     require(typeMemberType =:= expectedType, s"Type member $name in $owner is $typeMemberType, must be $expectedType")
   }

    private def checkExistentialTypeMember(owner: Type, name: TypeName, upperBound: Type): Unit = {
      val typeMemberType = owner.member(name).typeSignature
      require(typeMemberType.isInstanceOf[TypeBoundsApi], s"$typeMemberType is not a type bounds.")
      val TypeBounds(_, hi) = typeMemberType
      require(hi =:= upperBound, s"Type member $name in $owner is $typeMemberType, upper type bound must be $upperBound")
    }

    private def refineExistentialTypeParameter(owner: Type, parameterName: TypeName, upperBound: Type): Type => Type = {
      require(owner.isInstanceOf[ExistentialTypeApi], s"$owner is not existential.")

      val clazz = owner.typeSymbol.asClass
      val paramPosition = clazz.typeParams.indexWhere(_.name == parameterName)
      require(paramPosition > -1, s"Type parameter $parameterName not found in $clazz of $owner")

      val ExistentialType(existentialArgs, underlying) = owner
      val TypeRef(_, _, args) = underlying
      val existentialArg = args(paramPosition).typeSymbol
      val existentialArgSymbol = existentialArg
      require(existentialArgs.contains(existentialArgSymbol), s"Argument $existentialArg for type parameter $parameterName in $owner is not existential.")

      val existentialArgBounds = existentialArgSymbol.typeSignature
      val TypeBounds(lo, hi) = existentialArgBounds
      require(hi =:= upperBound, s"Existential argument for parameter $parameterName in $owner is $existentialArg: $existentialArgBounds, upper type bound must be $upperBound.")

      { case refinedArgument: Type =>
        require(refinedArgument <:< hi, s"Argument $refinedArgument for parameter $parameterName in $owner must be a subtype of $hi from $existentialArg: $existentialArgBounds")
        require(lo <:< refinedArgument, s"Argument $refinedArgument for parameter $parameterName in $owner must be a supertype of $lo from $existentialArg: $existentialArgBounds")
        underlying.substituteTypes(existentialArgSymbol::Nil, refinedArgument::Nil)
      }
    }
   }
