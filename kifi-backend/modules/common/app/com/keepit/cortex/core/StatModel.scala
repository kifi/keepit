package com.keepit.cortex.core

import play.api.libs.json._

trait StatModel

case class ModelVersion[M <: StatModel](version: Int) extends AnyVal{
  override def toString = version.toString
}

object ModelVersion{
  def format[M <: StatModel]: Format[ModelVersion[M]] =
    Format(
      __.read[Int].map(ModelVersion(_)),
      new Writes[ModelVersion[M]]{ def writes(o: ModelVersion[M]) = JsNumber(o.version) }
    )
}

trait Versionable[M <: StatModel]

trait BinaryFormatter[M <: StatModel]{
  def toBinary(m: M): Array[Byte]
  def fromBinary(bytes: Array[Byte]): M
}
