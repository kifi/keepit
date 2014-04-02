package com.keepit.cortex.plugins

import com.keepit.cortex.core.StatModel
import com.keepit.cortex.store.VersionedStore
import com.keepit.cortex.core.Versionable
import com.keepit.cortex.core.FeatureRepresenter
import com.keepit.cortex.core.ModelVersion
import org.joda.time.DateTime
import play.api.libs.json._
import play.api.libs.functional.syntax._
import com.keepit.cortex.store.S3CommitInfoStore
import com.keepit.common.time._
import com.keepit.cortex.store.CommitInfoStore
import com.keepit.common.db.ModelWithSeqNumber
import com.keepit.common.db.SequenceNumber
import com.keepit.cortex.core.FeatureRepresentation


case class CommitInfo[T, M <: StatModel](
  seq: FeatureStoreSequenceNumber[T, M],
  version: ModelVersion[M],
  committedAt: DateTime
)

object CommitInfo {
  def infoReads[T, M <: StatModel]: Reads[CommitInfo[T, M]] = new Reads[CommitInfo[T, M]]{
    def reads(json: JsValue): JsResult[CommitInfo[T, M]] = {
      val seq = (json \ "seq").as[Long]
      val ver = (json \ "version").as[Int]
      val time = DateTimeJsonFormat.reads(json \ "committedAt").get
      JsSuccess(CommitInfo[T, M](FeatureStoreSequenceNumber[T, M](seq), new ModelVersion[M](ver), time))
    }
  }

  def infoWrites[T, M <: StatModel]: Writes[CommitInfo[T, M]] = new Writes[CommitInfo[T, M]]{
    def writes(info: CommitInfo[T, M]) = Json.obj("seq" -> info.seq.value, "version" -> info.version.version, "committedAt" -> DateTimeJsonFormat.writes(info.committedAt))
  }

  def format[T, M <: StatModel]: Format[CommitInfo[T, M]] = Format(infoReads[T, M], infoWrites[T, M])
}

case class CommitInfoKey[T, M <: StatModel](version: ModelVersion[M]){
  override def toString(): String = "commitInfo_version_" + version.version
}

case class FeatureStoreSequenceNumber[T, M <: StatModel](value: Long) extends Ordered[FeatureStoreSequenceNumber[T, M]] {
  def compare(that: FeatureStoreSequenceNumber[T, M]) = value compare that.value
//  def +(offset: Long): FeatureStoreSequenceNumber[T, M] = FeatureStoreSequenceNumber[T, M](this.value + offset)
//  def -(other: FeatureStoreSequenceNumber[T, M]): Long = this.value - other.value
//  def max(other: FeatureStoreSequenceNumber[T, M]): FeatureStoreSequenceNumber[T, M] = FeatureStoreSequenceNumber[T, M](this.value max other.value)
  override def toString = value.toString
}

trait FeatureUpdatePlugin[T, M <: StatModel]{
  def update(): Unit
  def recomputeAll(): Unit
  def commitInfo(): Option[CommitInfo[T, M]]
}

class FeatureUpdateActor[K, T, M <: StatModel](
  updater: FeatureUpdater[K, T, M]
)

trait DataPuller[T] {
  def getSince(lowSeq: SequenceNumber[T], limit: Int): Seq[T]
  def getBetween(lowSeq: SequenceNumber[T], highSeq: SequenceNumber[T]): Seq[T]
}


// K: key for versionedStore
abstract class FeatureUpdater[K, T, M <: StatModel](
  representer: FeatureRepresenter[T, M],
  featureStore: VersionedStore[K, M, FeatureRepresentation[T, M]],
  commitInfoStore: CommitInfoStore[T, M],
  dataPuller: DataPuller[T]
){

  // abstract methods
  protected def getSeqNumber(datum: T): SequenceNumber[T]
  protected def genFeatureKey(datum: T): K

  protected val pullSize = 500

  private var currentSequence: FeatureStoreSequenceNumber[T, M] = {
    getCommitInfoFromStore() match {
      case Some(info) => {
        assume(info.version == representer.version)
        FeatureStoreSequenceNumber[T, M](info.seq.value)
      }
      case None => FeatureStoreSequenceNumber[T, M](-1L)
    }
  }

  private def genCommitInfoKey(): CommitInfoKey[T, M] = CommitInfoKey[T, M](representer.version)

  protected def getCommitInfoFromStore(): Option[CommitInfo[T, M]] = {
    val key = genCommitInfoKey()
    commitInfoStore.get(key)
  }

  private def commit(): Unit = {
    val commitData = CommitInfo(currentSequence, representer.version, currentDateTime)
    val key = genCommitInfoKey()
    commitInfoStore.+=(key, commitData)
  }

  def update(): Unit = {
    val ents = dataPuller.getSince(SequenceNumber[T](currentSequence.value), limit = pullSize)
    val maxSeq = ents.map{ent => getSeqNumber(ent)}.max
    val entsAndFeat = ents.map{ ent => (genFeatureKey(ent), representer.apply(ent))}
    entsAndFeat.foreach{ case (k, vOpt) =>
      vOpt.foreach{ v =>
        featureStore.+=(k, representer.version, v)
      }
    }
    currentSequence = FeatureStoreSequenceNumber[T, M](maxSeq.value)
    commit()
  }

  def commitInfo(): Option[CommitInfo[T, M]] = getCommitInfoFromStore()
}
