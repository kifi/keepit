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
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.akka.FortyTwoActor
import com.keepit.common.actor.ActorInstance
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.common.plugin.SchedulerPlugin
import scala.concurrent.duration._
import com.keepit.cortex.store.FeatureStoreSequenceNumber
import com.keepit.cortex.store.CommitInfo
import com.keepit.cortex.store.CommitInfoKey
import com.keepit.common.logging.Logging


trait FeatureUpdatePlugin[T, M <: StatModel] extends SchedulerPlugin{
  def update(): Unit
}

object FeaturePluginMessages{
  case object Update
}

abstract class BaseFeatureUpdatePlugin[K, T, M<: StatModel](
  actor: ActorInstance[FeatureUpdateActor[K, T, M]],
  serviceDiscovery: ServiceDiscovery
) extends FeatureUpdatePlugin[T, M]{

  import FeaturePluginMessages._

  override def enabled: Boolean = true

  val name: String = getClass.toString

  override def onStart() {
    log.info(s"starting $name")
    scheduleTaskOnLeader(actor.system, 30 seconds, 1 minutes, actor.ref, Update)
  }

  override def onStop() {
    log.info(s"stopping $name")
  }

  override def update(): Unit = {
    actor.ref ! Update
  }

}

abstract class FeatureUpdateActor[K, T, M <: StatModel](
  airbrake: AirbrakeNotifier,
  updater: FeatureUpdater[K, T, M]
) extends FortyTwoActor(airbrake) {
  import FeaturePluginMessages._

  def receive() = {
    case Update => updater.update()
  }
}

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
) extends Logging {

  // abstract methods
  protected def getSeqNumber(datum: T): SequenceNumber[T]
  protected def genFeatureKey(datum: T): K

  protected val pullSize = 500
  protected val name: String = getClass.toString

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
    log.info(s"$name: begin a new round of update")
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
