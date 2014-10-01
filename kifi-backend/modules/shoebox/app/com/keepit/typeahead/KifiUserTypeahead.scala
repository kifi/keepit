package com.keepit.typeahead

import com.google.inject.Inject
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
import scala.concurrent.duration.Duration
import com.keepit.common.concurrent.ExecutionContext
import com.keepit.common.time._
import org.joda.time.Minutes
import com.keepit.common.cache.TransactionalCaching.Implicits.directCacheAccess

class KifiUserTypeahead @Inject() (
    db: Database,
    override val airbrake: AirbrakeNotifier,
    cache: KifiUserTypeaheadCache,
    store: KifiUserTypeaheadStore,
    userRepo: UserRepo,
    userConnectionRepo: UserConnectionRepo,
    UserCache: UserIdCache) extends Typeahead[User, User, User] with Logging { // User as info might be too heavy
  implicit val fj = ExecutionContext.fj

  def refreshAll(): Future[Unit] = {
    val userIds = db.readOnlyReplica { implicit ro =>
      userRepo.getAllActiveIds()
    }
    refreshByIds(userIds)
  }

  def refresh(userId: Id[User]): Future[PrefixFilter[User]] = {
    build(userId).map { filter =>
      cache.set(KifiUserTypeaheadKey(userId), filter)
      store += (userId -> filter)
      log.info(s"[refresh($userId)] cache/store updated; filter=$filter")
      filter
    }(ExecutionContext.fj)
  }

  protected def extractName(info: User): String = info.fullName

  protected def getAllInfos(id: Id[User]): Future[Seq[(Id[User], User)]] = {
    db.readOnlyMasterAsync { implicit ro =>
      userConnectionRepo.getConnectedUsers(id)
    } flatMap { ids =>
      log.info(s"[getAllInfosForUser($id)] connectedUsers:(len=${ids.size}):${ids.mkString(",")}")
      getInfos(id, ids.toSeq).map(_.map(user => user.id.get -> user))
    }
  }

  protected def getInfos(userId: Id[User], ids: Seq[Id[User]]): Future[Seq[User]] = SafeFuture {
    if (ids.isEmpty) Seq.empty
    else {
      db.readOnlyMaster { implicit ro =>
        userRepo.getUsers(ids).valuesIterator.toVector
      }
    }
  }

  protected def getOrCreatePrefixFilter(userId: Id[User]): Future[PrefixFilter[User]] = {
    cache.getOrElseFuture(KifiUserTypeaheadKey(userId)) {
      val res = store.getWithMetadata(userId)
      res match {
        case Some((filter, meta)) =>
          if (meta.exists(m => m.lastModified.plusMinutes(15).isBefore(currentDateTime))) {
            log.info(s"[asyncGetOrCreatePrefixFilter($userId)] filter EXPIRED (lastModified=${meta.get.lastModified}); (curr=${currentDateTime}); (delta=${Minutes.minutesBetween(meta.get.lastModified, currentDateTime)} minutes) - rebuild")
            refresh(userId)
          }
          Future.successful(filter)
        case None => refresh(userId)
      }
    }
  }
}

trait KifiUserTypeaheadStore extends PrefixFilterStore[User, User]

class S3KifiUserTypeaheadStore @Inject() (bucket: S3Bucket, amazonS3Client: AmazonS3, accessLog: AccessLog) extends S3PrefixFilterStoreImpl[User, User](bucket, amazonS3Client, accessLog) with KifiUserTypeaheadStore

class InMemoryKifiUserTypeaheadStoreImpl extends InMemoryPrefixFilterStoreImpl[User, User] with KifiUserTypeaheadStore

class KifiUserTypeaheadCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends BinaryCacheImpl[KifiUserTypeaheadKey, PrefixFilter[User]](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

case class KifiUserTypeaheadKey(userId: Id[User]) extends Key[PrefixFilter[User]] {
  val namespace = "kifi_user_typeahead"
  override val version = 1
  def toKey() = userId.id.toString
}