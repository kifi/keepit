package com.keepit.search.index.user

import com.google.inject.Inject
import com.keepit.common.actor.ActorInstance
import com.keepit.common.db.SequenceNumber
import com.keepit.common.logging.Logging
import com.keepit.common.plugin.SchedulingProperties
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.model.User
import com.keepit.search.index._
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.shoebox.ShoeboxServiceClient
import scala.concurrent.Future

class UserIndexer(indexDirectory: IndexDirectory, shoebox: ShoeboxServiceClient, val airbrake: AirbrakeNotifier) extends Indexer[User, User, UserIndexer](indexDirectory, UserFields.PREFIX_MAX_LEN) {
  val name = "UserIndexer"

  def update(): Int = throw new UnsupportedOperationException()

  import play.api.libs.concurrent.Execution.Implicits.defaultContext

  def asyncUpdate(): Future[Boolean] = updateLock.synchronized {
    resetSequenceNumberIfReindex()
    val fetchSize = commitBatchSize
    fetchIndexables(sequenceNumber, fetchSize).map {
      case (indexables, exhausted) =>
        processIndexables(indexables)
        exhausted
    }
  }

  private def fetchIndexables(seq: SequenceNumber[User], fetchSize: Int): Future[(Seq[UserIndexable], Boolean)] = {
    shoebox.getUserIndexable(sequenceNumber, fetchSize).flatMap {
      case Seq() => Future.successful((Seq.empty[UserIndexable], true))
      case users =>
        val userIds = users.map(_.id.get)
        val futureEmails = shoebox.getEmailAddressesForUsers(userIds)
        val futureExperiments = shoebox.getExperimentsByUserIds(userIds)
        for {
          emailsByUserId <- futureEmails
          experimentsByUserId <- futureExperiments
        } yield {
          val indexables = users.map { user =>
            val emails = emailsByUserId.getOrElse(user.id.get, Seq.empty).toSet
            val experiments = experimentsByUserId.getOrElse(user.id.get, Set.empty)
            new UserIndexable(user, emails, experiments)
          }

          val exhausted = users.length < fetchSize
          (indexables, exhausted)
        }
    }
  }

  private def processIndexables(indexables: Seq[UserIndexable]): Int = updateLock.synchronized {
    doUpdate(name)(indexables.iterator)
  }

  override def indexInfos(name: String): Seq[IndexInfo] = {
    super.indexInfos(this.name)
  }
}

class UserIndexerActor @Inject() (
    airbrake: AirbrakeNotifier,
    indexer: UserIndexer) extends CoordinatingIndexerActor(airbrake, indexer) with Logging {

  protected def update(): Future[Boolean] = indexer.asyncUpdate()
}

trait UserIndexerPlugin extends IndexerPlugin[User, UserIndexer]

class UserIndexerPluginImpl @Inject() (
  actor: ActorInstance[UserIndexerActor],
  indexer: UserIndexer,
  airbrake: AirbrakeNotifier,
  serviceDiscovery: ServiceDiscovery,
  val scheduling: SchedulingProperties) extends IndexerPluginImpl(indexer, actor, serviceDiscovery) with UserIndexerPlugin

