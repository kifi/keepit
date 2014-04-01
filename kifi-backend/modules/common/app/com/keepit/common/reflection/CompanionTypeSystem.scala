package com.keepit.common.reflection

import scala.reflect.runtime.universe._
import scala.util.{Failure, Success, Try}

object CompanionTypeSystem {

  def apply[SealedClass: TypeTag, Companion: TypeTag](forwardTypeReference: String): Set[Companion] = {
    val sealedClassType = typeOf[SealedClass]
    val companionType = typeOf[Companion]
    val forwardTypeUpperBound = sealedClassType
    val refineCompanionWithForwardTypeParameter = getTypeWithForwardTypeParameterOrElseCheckTypeMember(companionType, forwardTypeReference, forwardTypeUpperBound)
    val refineSealedClassWithForwardTypeParameter = getTypeWithForwardTypeParameterOrElseCheckTypeMember(sealedClassType, forwardTypeReference, forwardTypeUpperBound)

    getSubclasses(sealedClassType).map { subclass =>
      val subclassType = subclass.toType

      refineSealedClassWithForwardTypeParameter match {
        case Some(refineSealedClassTypeWith) =>
          val expectedSubclassType = refineSealedClassTypeWith(subclassType)
          require(subclassType <:< expectedSubclassType, s"Class $subclassType must extend $expectedSubclassType")
        case None =>
          require(subclassType <:< sealedClassType, s"Class $subclassType must extend $sealedClassType")
          val expectedForwardType = subclassType
          checkTypeMember(subclassType, forwardTypeReference, expectedForwardType, strict = true)
      }

      val subclassCompanionType = subclass.companionSymbol.typeSignature

      refineCompanionWithForwardTypeParameter match {
        case Some(refineCompanionTypeWith) =>
          val expectedSubclassCompanionType = refineCompanionTypeWith(subclassType)
          require(subclassCompanionType <:< expectedSubclassCompanionType, s"Companion object $subclassCompanionType of $subclass must extend $expectedSubclassCompanionType")
        case None =>
          require(subclassCompanionType <:< companionType, s"Companion object $subclassCompanionType of $subclass must extend $companionType")
          val expectedForwardType = subclassType
          checkTypeMember(subclassCompanionType, forwardTypeReference, expectedForwardType, strict = true)
      }
      getCompanion(subclass).asInstanceOf[Companion]
   }
  }

  private def getTypeWithForwardTypeParameterOrElseCheckTypeMember(owner: Type, forwardTypeReference: TypeName, forwardTypeUpperBound: Type): Option[Type => Type] = {
    Try { refineExistentialTypeParameter(owner, forwardTypeReference, forwardTypeUpperBound) } match {
      case Success(typeWithForwardTypeParameter) => Some(typeWithForwardTypeParameter)
      case Failure(forwardTypeParameterException) => {
        Try(checkTypeMember(owner, forwardTypeReference, forwardTypeUpperBound, strict = false)) match {
          case Success(_) => None
          case Failure(forwardTypeMemberException) =>
            throw new IllegalArgumentException(s"Could not find a valid forward type reference $forwardTypeReference in $owner: \n No valid type parameter (${forwardTypeParameterException.getMessage}) \n No valid type member (${forwardTypeMemberException.getMessage})")
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

   private def checkTypeMember(owner: Type, name: TypeName, expectedType: Type, strict: Boolean): Unit = {
     val typeMemberType = owner.member(name).typeSignature
     if (strict) require(typeMemberType =:= expectedType, s"Type member $name in $owner is $typeMemberType, must be $expectedType")
     else {
       require(typeMemberType.isInstanceOf[TypeBoundsApi], s"$typeMemberType is not a type bounds.")
       val TypeBounds(_, hi) = typeMemberType
       require(hi =:= expectedType, s"Type member $name in $owner is $typeMemberType, upper type bound must be $expectedType")
     }
   }

    private def refineExistentialTypeParameter(existentialType: Type, parameterName: TypeName, expectedUpperType: Type): Type => Type = {
      require(existentialType.isInstanceOf[ExistentialTypeApi], s"$existentialType is not existential.")

      val clazz = existentialType.typeSymbol.asClass
      val paramPosition = clazz.typeParams.indexWhere(_.name == parameterName)
      require(paramPosition > -1, s"Type parameter $parameterName not found in $clazz of $existentialType")

      val ExistentialType(existentialArgs, underlying) = existentialType
      val TypeRef(_, _, args) = underlying
      val existentialArg = args(paramPosition).typeSymbol
      val existentialArgSymbol = existentialArg
      require(existentialArgs.contains(existentialArgSymbol), s"Argument $existentialArg for type parameter $parameterName in $existentialType is not existential.")

      val existentialArgBounds = existentialArgSymbol.typeSignature
      val TypeBounds(lo, hi) = existentialArgBounds
      require(hi =:= expectedUpperType, s"Existential argument for parameter $parameterName in $existentialType is $existentialArg: $existentialArgBounds, upper type bound must be $expectedUpperType.")

      { case refinedArgument: Type =>
        require(refinedArgument <:< hi, s"Argument $refinedArgument for parameter $parameterName in $existentialType must be a subtype of $hi from $existentialArg: $existentialArgBounds")
        require(lo <:< refinedArgument, s"Argument $refinedArgument for parameter $parameterName in $existentialType must be a supertype of $lo from $existentialArg: $existentialArgBounds")
        underlying.substituteTypes(existentialArgSymbol::Nil, refinedArgument::Nil)
      }
    }
   }
