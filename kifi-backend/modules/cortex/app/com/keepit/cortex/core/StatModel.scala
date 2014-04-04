package com.keepit.cortex.core

import play.api.libs.json._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.concurrent.Future
import com.keepit.cortex.store.StatModelStore


trait StatModel

case class ModelVersion[M <: StatModel](version: Int){
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

trait ModelLoader[M <: StatModel] {
  def load(version: ModelVersion[M]): Option[M]
  def asyncLoad(version: ModelVersion[M]): Future[Option[M]] = Future{ load(version) }
}

abstract class StoreBasedModelLoader[M <: StatModel](store: StatModelStore[M]) extends ModelLoader[M]{
  override def load(version: ModelVersion[M]): Option[M] = store.get(version)
}

trait BinaryFormatter[M <: StatModel]{
  def toBinary(m: M): Array[Byte]
  def fromBinary(bytes: Array[Byte]): M
}
