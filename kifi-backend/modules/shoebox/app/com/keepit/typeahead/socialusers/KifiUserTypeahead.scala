package com.keepit.typeahead.socialusers

import com.google.inject.Inject
import com.keepit.common.akka.SafeFuture
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.typeahead._
import com.keepit.model._
import com.keepit.common.logging.{AccessLog, Logging}
import com.keepit.common.db.Id
import scala.concurrent.{Promise, Future}
import com.keepit.common.store.S3Bucket
import com.amazonaws.services.s3.AmazonS3
import com.keepit.common.store.S3Bucket
import com.keepit.common.cache.{Key, BinaryCacheImpl, FortyTwoCachePlugin, CacheStatistics}
import scala.concurrent.duration.Duration
import com.keepit.serializer.ArrayBinaryFormat
import com.keepit.common.concurrent.ExecutionContext
import com.keepit.common.time._
import org.joda.time.Minutes
import com.keepit.common.cache.TransactionalCaching.Implicits.directCacheAccess

class KifiUserTypeahead @Inject()(
  db: Database,
  override val airbrake: AirbrakeNotifier,
  cache: KifiUserTypeaheadCache,
  store: KifiUserTypeaheadStore,
  userRepo: UserRepo,
  userConnectionRepo: UserConnectionRepo,
  UserCache: UserIdCache
) extends Typeahead[User, User] with Logging { // User as info might be too heavy

  implicit val fj = ExecutionContext.fj

  def refreshAll(): Future[Unit] = {
    val userIds = db.readOnlyMaster { implicit ro =>
      userRepo.getAllActiveIds()
    }
    refreshByIds(userIds)
  }

  def refresh(userId: Id[User]): Future[PrefixFilter[User]] = {
    build(userId).map { filter =>
      cache.set(KifiUserTypeaheadKey(userId), filter.data)
      store += (userId -> filter.data)
      log.info(s"[refresh($userId)] cache/store updated; filter=$filter")
      filter
    }(ExecutionContext.fj)
  }

  protected def extractName(info: User): String = info.fullName

  protected def extractId(info: User): Id[User] = info.id.get

  protected def getAllInfosForUser(id: Id[User]): Future[Seq[User]] = {
    db.readOnlyMasterAsync { implicit ro =>
      userConnectionRepo.getConnectedUsers(id)
    } flatMap { ids =>
      log.info(s"[getAllInfosForUser($id)] connectedUsers:(len=${ids.size}):${ids.mkString(",")}")
      getInfos(ids.toSeq)
    }
  }

  protected def getInfos(ids: Seq[Id[User]]): Future[Seq[User]] = SafeFuture {
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
        case None => refresh(userId).map(_.data)
      }
    }.map{ new PrefixFilter[User](_) }
  }
}

trait KifiUserTypeaheadStore extends PrefixFilterStore[User]

class S3KifiUserTypeaheadStore @Inject() (bucket: S3Bucket, amazonS3Client: AmazonS3, accessLog: AccessLog) extends S3PrefixFilterStoreImpl[User](bucket, amazonS3Client, accessLog) with KifiUserTypeaheadStore

class InMemoryKifiUserTypeaheadStoreImpl extends InMemoryPrefixFilterStoreImpl[User] with KifiUserTypeaheadStore

class KifiUserTypeaheadCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends BinaryCacheImpl[KifiUserTypeaheadKey, Array[Long]](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)(ArrayBinaryFormat.longArrayFormat)

case class KifiUserTypeaheadKey(userId: Id[User]) extends Key[Array[Long]] {
  val namespace = "kifi_user_typeahead"
  override val version = 1
  def toKey() = userId.id.toString
}