package com.keepit.serializer

import play.api.libs.json.{JsResult, JsValue, Json, Format}

trait Companion[T] {
  def typeCode: TypeCode[T]
  def format: Format[T]
  implicit def companion: Companion[T] = this
  implicit def instanceToCompanion(instance: T): Companion[T] = this
}

case class TypeCode[+T](code: String) {
  override def toString = code
}

object TypeCode {
  def integrityCheck[T](typeCodes: Seq[TypeCode[T]]): Boolean = {
    val typeCodeStrings = typeCodes.map(_.toString)
    typeCodeStrings.length == typeCodeStrings.toSet.size
  }
  def typeCodeMap[T](typeCodes: TypeCode[T]*): Map[String, TypeCode[T]] = {
    require(TypeCode.integrityCheck(typeCodes), "Duplicate type codes")
    typeCodes.map(typeCode => typeCode.toString -> typeCode).toMap
  }
}

object Companion {
  def companionByTypeCode[T](companions: Companion[_ <: T]*): Map[String, Companion[_ <: T]] = {
    require(TypeCode.integrityCheck(companions.map(_.typeCode)), "Duplicate type codes")
    companions.map(companion => companion.typeCode.toString -> companion).toMap
  }

  def writes[E: Companion, T >: E](instance: E) = Json.obj("typeCode" -> implicitly[Companion[E]].typeCode.toString, "value" -> implicitly[Companion[E]].format.writes(instance))
  def reads[T](companions: Companion[_ <: T]*): JsValue => JsResult[T] = { // Not using currying syntactic sugar for optimization
    val getCompanion = companionByTypeCode[T](companions: _*)
    json: JsValue => getCompanion((json \ "typeCode").as[String]).format.reads((json \ "value"))
  }
}
