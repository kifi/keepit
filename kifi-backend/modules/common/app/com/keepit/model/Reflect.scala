package com.keepit.model

import scala.reflect.runtime.universe._

object Reflect {

  def getCompanionTypeSystem[SealedClass: TypeTag, Companion: TypeTag](forwardTypeMember: String): Set[Companion] =
    getSubclasses[SealedClass].map { subclass =>
     checkCompanionType[Companion](subclass)
     val subclassType = subclass.toType
     val companionType = subclass.companionSymbol.typeSignature
     checkTypeMember(subclassType, forwardTypeMember, subclassType)
     checkTypeMember(companionType, forwardTypeMember, subclassType)
     getCompanion(subclass).asInstanceOf[Companion]
   }

  private def getCompanion(clazz: ClassSymbol): Any = {
    val m = runtimeMirror(getClass.getClassLoader)
    m.reflectModule(clazz.companionSymbol.asModule).instance
  }

  private def getSubclasses[SealedClass: TypeTag]: Set[ClassSymbol] = {
    val clazz = typeOf[SealedClass].typeSymbol.asClass
    require(clazz.isSealed, s"$clazz must be sealed.")
    clazz.knownDirectSubclasses.map(_.asClass)
  }

   private def checkCompanionType[ExpectedCompanion: TypeTag](clazz: ClassSymbol) = {
     val companionType = clazz.companionSymbol.typeSignature
     val expectedCompanionType = typeOf[ExpectedCompanion]
     require(companionType <:< expectedCompanionType, s"$companionType must extend $expectedCompanionType")
   }

   private def checkTypeMember(owner: Type, name: TypeName, expectedType: Type) = {
     val typeMemberType = owner.member(name).typeSignature
     require(typeMemberType =:= expectedType, s"Type member $name in $owner is $typeMemberType, expected $expectedType")
   }
 }
