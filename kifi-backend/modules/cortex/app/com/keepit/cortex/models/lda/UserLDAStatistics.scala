package com.keepit.cortex.models.lda

import com.google.inject.{ Singleton, ImplementedBy, Inject }
import com.keepit.common.actor.ActorInstance
import com.keepit.common.akka.FortyTwoActor
import com.keepit.common.cache.{ JsonCacheImpl, FortyTwoCachePlugin, CacheStatistics, Key }
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.{ AccessLog, Logging }
import com.keepit.common.plugin.{ SchedulerPlugin, SchedulingProperties }
import com.keepit.common.time._
import com.keepit.cortex.MiscPrefix
import com.keepit.cortex.core.{ Versionable, ModelVersion }
import com.keepit.cortex.dbmodel.UserLDAInterestsRepo
import com.keepit.cortex.utils.MatrixUtils._
import org.joda.time.DateTime
import play.api.libs.json.Json
import scala.concurrent.duration._

// can be used to standarize data to zero mean, unit variance.
case class UserLDAStatistics(
  updatedAt: DateTime,
  version: ModelVersion[DenseLDA],
  mean: Array[Float],
  std: Array[Float],
  min: Array[Float],
  max: Array[Float]) extends Versionable[DenseLDA]

object UserLDAStatistics {
  implicit val format = Json.format[UserLDAStatistics]
}

case class UserLDAStatisticsCacheKey(modelVersion: ModelVersion[DenseLDA]) extends Key[UserLDAStatistics] {
  override val version = 1
  val namespace = "user_lda_stats"
  def toKey(): String = modelVersion.version.toString
}

class UserLDAStatisticsCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[UserLDAStatisticsCacheKey, UserLDAStatistics](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

class UserLDAStatisticsActor @Inject() (airbrake: AirbrakeNotifier, updater: UserLDAStatisticsUpdater) extends FortyTwoActor(airbrake) {
  import UserLDAStatisticsActor._
  def receive = {
    case UpdateUserLDAStatistics => updater.update()
  }
}

object UserLDAStatisticsActor {
  sealed trait UserLDAStatisticsActorMessage
  object UpdateUserLDAStatistics extends UserLDAStatisticsActorMessage
}

trait UserLDAStatisticsPlugin

@Singleton
class UserLDAStatisticsPluginImpl @Inject() (
    actor: ActorInstance[UserLDAStatisticsActor],
    val scheduling: SchedulingProperties) extends SchedulerPlugin with UserLDAStatisticsPlugin {
  import UserLDAStatisticsActor._

  override def enabled: Boolean = true

  override def onStart() {
    scheduleTaskOnOneMachine(actor.system, 5 minutes, 12 hours, actor.ref, UpdateUserLDAStatistics, UpdateUserLDAStatistics.getClass.getSimpleName)
  }
}

@Singleton
class UserLDAStatisticsUpdater @Inject() (
    db: Database,
    userTopicRepo: UserLDAInterestsRepo,
    representer: MultiVersionedLDAURIRepresenter,
    statsStore: UserLDAStatisticsStore,
    cache: UserLDAStatisticsCache) extends Logging {

  import com.keepit.common.cache.TransactionalCaching.Implicits.directCacheAccess

  private val MIN_EVIDENCE = 100

  def update() {
    representer.versions.foreach { version => update(version) }
  }

  def update(version: ModelVersion[DenseLDA]) {
    val (_, interests) = db.readOnlyReplica { implicit s => userTopicRepo.getAllUserTopicMean(version, MIN_EVIDENCE) }
    val vecs = interests.map { _.mean }
    log.info(s"begin user LDA stats update. data size: ${vecs.size}")
    if (vecs.size > 1) {
      val stats = genStats(vecs, version)
      statsStore.+=(MiscPrefix.LDA.userLDAStatsJsonFile, version, stats)
      cache.set(UserLDAStatisticsCacheKey(version), stats)
    }
  }

  private def genStats(vecs: Seq[Array[Float]], version: ModelVersion[DenseLDA]): UserLDAStatistics = {
    val (min, max) = getMinAndMax(vecs)
    val (mean, std) = getMeanAndStd(vecs)
    UserLDAStatistics(currentDateTime, version, mean, std, min, max)
  }
}

@ImplementedBy(classOf[UserLDAStatisticsRetrieverImpl])
trait UserLDAStatisticsRetriever {
  def getUserLDAStats(version: ModelVersion[DenseLDA]): Option[UserLDAStatistics]
}

@Singleton
class UserLDAStatisticsRetrieverImpl @Inject() (
    cache: UserLDAStatisticsCache,
    statsStore: UserLDAStatisticsStore) extends UserLDAStatisticsRetriever {

  import com.keepit.common.cache.TransactionalCaching.Implicits.directCacheAccess

  def getUserLDAStats(version: ModelVersion[DenseLDA]): Option[UserLDAStatistics] = {
    cache.getOrElseOpt(UserLDAStatisticsCacheKey(version)) {
      statsStore.syncGet(MiscPrefix.LDA.userLDAStatsJsonFile, version)
    }
  }
}
