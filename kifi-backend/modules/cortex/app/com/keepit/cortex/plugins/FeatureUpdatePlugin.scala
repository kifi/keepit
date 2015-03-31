package com.keepit.cortex.plugins

import com.keepit.cortex.core.StatModel
import com.keepit.cortex.store.VersionedStore
import com.keepit.cortex.core.FeatureRepresenter
import com.keepit.common.time._
import com.keepit.cortex.store.CommitInfoStore
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
import scala.util.Random

trait FeatureUpdatePlugin[T, M <: StatModel] extends SchedulerPlugin {
  val startTime: FiniteDuration = (30 + Random.nextInt(60)) seconds
  val updateFrequency: FiniteDuration = 2 minutes
  def update(): Unit
}

object FeaturePluginMessages {
  case object Update
}

abstract class BaseFeatureUpdatePlugin[K, T, M <: StatModel, FT <: FeatureRepresentation[T, M]](
    actor: ActorInstance[FeatureUpdateActor[K, T, M, FT]],
    serviceDiscovery: ServiceDiscovery) extends FeatureUpdatePlugin[T, M] {

  import FeaturePluginMessages._

  override def enabled: Boolean = true

  val name: String = getClass.toString

  override def onStart() {
    scheduleTaskOnOneMachine(actor.system, startTime, updateFrequency, actor.ref, Update, this.getClass.getSimpleName + Update.getClass.getSimpleName)
  }

  override def update(): Unit = {
    actor.ref ! Update
  }

}

abstract class FeatureUpdateActor[K, T, M <: StatModel, FT <: FeatureRepresentation[T, M]](
    airbrake: AirbrakeNotifier,
    updater: BaseFeatureUpdater[K, T, M, FT]) extends FortyTwoActor(airbrake) {
  import FeaturePluginMessages._

  def receive() = {
    case Update => updater.update()
  }
}

trait DataPuller[T] {
  def getSince(lowSeq: SequenceNumber[T], limit: Int): Seq[T]
}

trait BaseFeatureUpdater[K, T, M <: StatModel, FT <: FeatureRepresentation[T, M]] {
  def update(): Unit
}

// K: key for versionedStore
abstract class FeatureUpdater[K, T, M <: StatModel, FT <: FeatureRepresentation[T, M]](
    representer: FeatureRepresenter[T, M, FT],
    featureStore: VersionedStore[K, M, FT],
    commitInfoStore: CommitInfoStore[T, M],
    dataPuller: DataPuller[T]) extends BaseFeatureUpdater[K, T, M, FT] with Logging {

  // abstract methods
  protected def getSeqNumber(datum: T): SequenceNumber[T]
  protected def genFeatureKey(datum: T): K

  protected val pullSize = 500
  protected val name: String = getClass.toString

  private def lastComittedSequence(): FeatureStoreSequenceNumber[T, M] = {
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
    commitInfoStore.syncGet(key)
  }

  private def commit(seq: FeatureStoreSequenceNumber[T, M]): Unit = {
    val commitData = CommitInfo(seq, representer.version, currentDateTime)
    val key = genCommitInfoKey()
    log.info(s"commiting data $commitData")
    commitInfoStore.+=(key, commitData)
  }

  def update(): Unit = {
    val lowSeq = lastComittedSequence()
    val ents = dataPuller.getSince(SequenceNumber[T](lowSeq.value), limit = pullSize)

    log.info(s"begin a new round of update. data size: ${ents.size}")
    val t1 = System.currentTimeMillis

    if (ents.isEmpty) return

    val maxSeq = ents.map { ent => getSeqNumber(ent) }.max
    val entsAndFeat = ents.map { ent => (genFeatureKey(ent), representer.apply(ent)) }
    entsAndFeat.foreach {
      case (k, vOpt) =>
        vOpt.foreach { v =>
          featureStore.+=(k, representer.version, v)
        }
    }

    log.info(s"update time elapse: ${(System.currentTimeMillis - t1) / 1000f} seconds")

    val commitSeq = FeatureStoreSequenceNumber[T, M](maxSeq.value)
    commit(commitSeq)
  }

  def commitInfo(): Option[CommitInfo[T, M]] = getCommitInfoFromStore()
}
