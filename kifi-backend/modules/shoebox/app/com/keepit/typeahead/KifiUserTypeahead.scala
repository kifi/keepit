package com.keepit.typeahead

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.akka.SafeFuture
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.model._
import com.keepit.common.logging.{ AccessLog, Logging }
import com.keepit.common.db.Id
import scala.concurrent.Future
import com.amazonaws.services.s3.AmazonS3
import com.keepit.common.store.S3Bucket
import com.keepit.common.cache.{ Key, BinaryCacheImpl, FortyTwoCachePlugin, CacheStatistics }
import scala.concurrent.duration._
import com.keepit.common.concurrent.{ FutureHelpers, ExecutionContext }
import com.keepit.common.time._
import org.joda.time.Minutes
import com.keepit.common.cache.TransactionalCaching.Implicits.directCacheAccess
import com.keepit.typeahead.KifiUserTypeahead.UserUserTypeahead

object KifiUserTypeahead {
  type UserUserTypeahead = PersonalTypeahead[User, User, User]
}

@Singleton
class KifiUserTypeahead @Inject() (
    db: Database,
    override val airbrake: AirbrakeNotifier,
    cache: KifiUserTypeaheadCache,
    store: KifiUserTypeaheadStore,
    userRepo: UserRepo,
    userConnectionRepo: UserConnectionRepo,
    libraryMembershipRepo: LibraryMembershipRepo,
    organizationMembershipRepo: OrganizationMembershipRepo,
    UserCache: UserIdCache) extends Typeahead[User, User, User, UserUserTypeahead] with Logging { // User as info might be too heavy
  implicit val fj = ExecutionContext.fj

  protected val refreshRequestConsolidationWindow = 5 seconds

  protected val fetchRequestConsolidationWindow = 15 seconds

  def refreshAll(): Future[Unit] = {
    var i = 0
    FutureHelpers.doUntil {
      db.readOnlyReplica { implicit session =>
        val userIds = userRepo.pageAscending(i, 50, Set(UserStates.INACTIVE)).map(_.id.get)
        i += 1
        refreshByIds(userIds).map { _ =>
          userIds.nonEmpty
        }
      }
    }
  }

  protected def create(userId: Id[User]) = {
    getAllInfos(userId).map { allInfos =>
      val filter = buildFilter(userId, allInfos)
      log.info(s"[refresh($userId)] cache/store updated; filter=$filter")
      makeTypeahead(userId, filter)
    }(ExecutionContext.fj)
  }

  protected def extractName(info: User): String = info.fullName

  protected def invalidate(typeahead: UserUserTypeahead): Unit = {
    val userId = typeahead.ownerId
    val filter = typeahead.filter
    store += (userId -> filter)
    cache.set(KifiUserTypeaheadKey(userId), filter)
  }

  private def makeTypeahead(userId: Id[User], filter: PrefixFilter[User]) = PersonalTypeahead(userId, filter, getInfos)

  private def getAllInfos(id: Id[User]): Future[Seq[(Id[User], User)]] = {
    db.readOnlyMasterAsync { implicit ro =>
      userConnectionRepo.getConnectedUsers(id) ++ organizationMembershipRepo.getTeammates(id) ++ libraryMembershipRepo.getCollaborators(id) + id
    } flatMap { ids =>
      getInfos(ids.toSeq).map(_.map(user => user.id.get -> user))
    }
  }

  private def getInfos(ids: Seq[Id[User]]): Future[Seq[User]] = SafeFuture {
    db.readOnlyMaster { implicit ro =>
      userRepo.getUsers(ids).valuesIterator.toVector
    }
  }

  protected def get(userId: Id[User]) = {
    val filterOpt = cache.getOrElseOpt(KifiUserTypeaheadKey(userId)) {
      store.getWithMetadata(userId).map {
        case (filter, meta) =>
          if (meta.exists(m => m.lastModified.plusMinutes(15).isBefore(currentDateTime))) {
            log.info(s"[asyncGetOrCreatePrefixFilter($userId)] filter EXPIRED (lastModified=${meta.get.lastModified}); (curr=${currentDateTime}); (delta=${Minutes.minutesBetween(meta.get.lastModified, currentDateTime)} minutes) - rebuild")
            refresh(userId)
          }
          filter
      }
    }
    Future.successful(filterOpt.map(makeTypeahead(userId, _)))
  }
}

trait KifiUserTypeaheadStore extends PrefixFilterStore[User, User]

class S3KifiUserTypeaheadStore @Inject() (bucket: S3Bucket, amazonS3Client: AmazonS3, accessLog: AccessLog) extends S3PrefixFilterStoreImpl[User, User](bucket, amazonS3Client, accessLog) with KifiUserTypeaheadStore

class InMemoryKifiUserTypeaheadStoreImpl extends InMemoryPrefixFilterStoreImpl[User, User] with KifiUserTypeaheadStore

class KifiUserTypeaheadCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends BinaryCacheImpl[KifiUserTypeaheadKey, PrefixFilter[User]](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

case class KifiUserTypeaheadKey(userId: Id[User]) extends Key[PrefixFilter[User]] {
  val namespace = "kifi_user_typeahead"
  override val version = 2
  def toKey() = userId.id.toString
}
