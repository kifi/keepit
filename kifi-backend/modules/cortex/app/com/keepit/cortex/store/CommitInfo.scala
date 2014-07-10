package com.keepit.cortex.store

import com.keepit.common.time.DateTimeJsonFormat
import com.keepit.cortex.core.StatModel
import com.keepit.cortex.core.ModelVersion
import play.api.libs.json._
import org.joda.time.DateTime

case class CommitInfo[T, M <: StatModel](
  seq: FeatureStoreSequenceNumber[T, M],
  version: ModelVersion[M],
  committedAt: DateTime)

object CommitInfo {
  def infoReads[T, M <: StatModel]: Reads[CommitInfo[T, M]] = new Reads[CommitInfo[T, M]] {
    def reads(json: JsValue): JsResult[CommitInfo[T, M]] = {
      val seq = (json \ "seq").as[Long]
      val ver = (json \ "version").as[Int]
      val time = DateTimeJsonFormat.reads(json \ "committedAt").get
      JsSuccess(CommitInfo[T, M](FeatureStoreSequenceNumber[T, M](seq), new ModelVersion[M](ver), time))
    }
  }

  def infoWrites[T, M <: StatModel]: Writes[CommitInfo[T, M]] = new Writes[CommitInfo[T, M]] {
    def writes(info: CommitInfo[T, M]) = Json.obj("seq" -> info.seq.value, "version" -> info.version.version, "committedAt" -> DateTimeJsonFormat.writes(info.committedAt))
  }

  def format[T, M <: StatModel]: Format[CommitInfo[T, M]] = Format(infoReads[T, M], infoWrites[T, M])
}

case class CommitInfoKey[T, M <: StatModel](version: ModelVersion[M]) {
  override def toString(): String = "commitInfo_version_" + version.version
}

case class FeatureStoreSequenceNumber[T, M <: StatModel](value: Long) extends Ordered[FeatureStoreSequenceNumber[T, M]] {
  def compare(that: FeatureStoreSequenceNumber[T, M]) = value compare that.value
  override def toString = value.toString
}
