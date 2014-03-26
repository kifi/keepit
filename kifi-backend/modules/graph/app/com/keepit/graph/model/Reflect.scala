package com.keepit.graph.model

import scala.reflect.runtime.universe._

object Reflect {
  def getCompanion(clazz: ClassSymbol): Any = {
    val m = runtimeMirror(getClass.getClassLoader)
    m.reflectModule(clazz.companionSymbol.asModule).instance
  }

  def getSubclasses[Clazz: TypeTag]: Set[ClassSymbol] = {
    val clazzClass = typeOf[Clazz].typeSymbol.asClass
    require(clazzClass.isSealed, s"$clazzClass must be sealed.")
    clazzClass.knownDirectSubclasses.map(_.asClass)
  }

  def checkDataReaderCompanions[DataReader: TypeTag, Kind: TypeTag] = getSubclasses[DataReader].foreach { subclass =>
    val companionType = subclass.companionSymbol.typeSignature
    val kindType = typeOf[Kind]
    require(companionType <:< kindType, s"$companionType must extend $kindType")
    val VType = companionType.member("V": TypeName).typeSignature
    val subclassType = subclass.toType
    require(VType =:= subclassType, s"$VType must be $subclassType")
  }
}
