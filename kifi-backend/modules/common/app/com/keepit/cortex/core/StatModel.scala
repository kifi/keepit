package com.keepit.cortex.core

import play.api.libs.json._
import play.api.mvc.QueryStringBindable
import com.keepit.cortex.models.lda.DenseLDA

trait StatModel

case class ModelVersion[M <: StatModel](version: Int) extends Ordered[ModelVersion[M]] {
  def compare(that: ModelVersion[M]) = version compare that.version
  override def toString = version.toString
}

case class StatModelName(name: String)

object StatModelName {
  val LDA = StatModelName("lda")
  val LDA_USER = StatModelName("lda_user")
  val LDA_USER_STATS = StatModelName("lda_user_stats")
  val LDA_LIBRARY = StatModelName("lda_library")
  val LDA_LIBRARY_CLEANUP = StatModelName("lda_library_cleanup") // not really a stat model
}

object ModelVersion {
  implicit def format[M <: StatModel]: Format[ModelVersion[M]] = Format(
    __.read[Int].map(ModelVersion(_)),
    new Writes[ModelVersion[M]] { def writes(o: ModelVersion[M]) = JsNumber(o.version) }
  )

  implicit def queryStringBinder[M <: StatModel](implicit intBinder: QueryStringBindable[Int]) = new QueryStringBindable[ModelVersion[M]] {
    override def bind(key: String, params: Map[String, Seq[String]]): Option[Either[String, ModelVersion[M]]] = {
      intBinder.bind(key, params) map {
        case Right(version) => Right(ModelVersion[M](version))
        case _ => Left("Unable to bind a ModelVersion")
      }
    }
    override def unbind(key: String, modelVersion: ModelVersion[M]): String = {
      intBinder.unbind(key, modelVersion.version)
    }
  }
}

trait Versionable[M <: StatModel]

trait BinaryFormatter[M <: StatModel] {
  def toBinary(m: M): Array[Byte]
  def fromBinary(bytes: Array[Byte]): M
}
