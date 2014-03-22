package com.keepit.cortex.core

import play.api.libs.json._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.concurrent.Future


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

trait ModelLoader {
  def load[M <: StatModel](version: ModelVersion[M]): Option[M]
  def asyncLoad[M <: StatModel](version: ModelVersion[M]): Future[Option[M]] = Future{ load(version) }
}

