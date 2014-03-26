package com.keepit.graph.model

import scala.reflect.runtime.universe._

object Reflect {
  def getCompanion(clazz: ClassSymbol): Any = {
    val m = runtimeMirror(getClass.getClassLoader)
    m.reflectModule(clazz.companionSymbol.asModule).instance
  }

  def getSubclasses[SealedClass: TypeTag]: Set[ClassSymbol] = {
    val clazz = typeOf[SealedClass].typeSymbol.asClass
    require(clazz.isSealed, s"$clazz must be sealed.")
    clazz.knownDirectSubclasses.map(_.asClass)
  }

  def checkDataReaders[DataReader: TypeTag, Kind: TypeTag] = getSubclasses[DataReader].foreach { subclass =>
    checkCompanionType[Kind](subclass)
    val subclassType = subclass.toType
    val companionType = subclass.companionSymbol.typeSignature
    checkTypeMember(subclassType, "V", subclassType)
    checkTypeMember(companionType, "V", subclassType)
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
