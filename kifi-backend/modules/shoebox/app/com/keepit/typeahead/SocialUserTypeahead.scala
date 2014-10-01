package com.keepit.typeahead

import com.keepit.common.akka.SafeFuture
import com.keepit.common.cache.BinaryCacheImpl
import com.keepit.common.cache.CacheStatistics
import com.keepit.common.cache.FortyTwoCachePlugin
import com.keepit.common.cache.Key
import com.keepit.common.cache.TransactionalCaching.Implicits.directCacheAccess
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.{ Logging, AccessLog }
import com.keepit.model._
import scala.concurrent.duration.Duration
import com.google.inject.Inject
import com.amazonaws.services.s3.AmazonS3
import com.keepit.common.store.S3Bucket
import scala.concurrent.Future
import com.keepit.common.concurrent.ExecutionContext
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.time._
import org.joda.time.Minutes

class SocialUserTypeahead @Inject() (
    db: Database,
    override val airbrake: AirbrakeNotifier,
    userRepo: UserRepo,
    store: SocialUserTypeaheadStore,
    cache: SocialUserTypeaheadCache,
    socialConnRepo: SocialConnectionRepo,
    socialUserRepo: SocialUserInfoRepo) extends Typeahead[User, SocialUserInfo, SocialUserBasicInfo] with Logging {

  implicit val fj = ExecutionContext.fj

  protected def getOrCreatePrefixFilter(userId: Id[User]): Future[PrefixFilter[SocialUserInfo]] = {
    cache.getOrElseFuture(SocialUserTypeaheadKey(userId)) {
      val res = store.getWithMetadata(userId)
      res match {
        case Some((filter, meta)) =>
          if (meta.exists(m => m.lastModified.plusMinutes(15).isBefore(currentDateTime))) {
            log.info(s"[asyncGetOrCreatePrefixFilter($userId)] filter EXPIRED (lastModified=${meta.get.lastModified}); (curr=${currentDateTime}); (delta=${Minutes.minutesBetween(meta.get.lastModified, currentDateTime)} minutes) - rebuild")
            refresh(userId) // async
          }
          Future.successful(filter)
        case None => refresh(userId)
      }
    }
  }

  protected def getInfos(userId: Id[User], ids: Seq[Id[SocialUserInfo]]): Future[Seq[SocialUserBasicInfo]] = {
    if (ids.isEmpty) Future.successful(Seq.empty[SocialUserBasicInfo])
    else {
      db.readOnlyMasterAsync { implicit session =>
        socialUserRepo.getSocialUserBasicInfos(ids).valuesIterator.toVector // do NOT use toSeq (=> toStream (lazy))
      }
    }
  }

  protected def getAllInfos(id: Id[User]): Future[Seq[SocialUserBasicInfo]] = SafeFuture {
    db.readOnlyMaster { implicit session =>
      socialConnRepo.getSocialConnectionInfosByUser(id).valuesIterator.flatten.toSeq
    }
  }

  override protected def extractId(info: SocialUserBasicInfo): Id[SocialUserInfo] = info.id

  override protected def extractName(info: SocialUserBasicInfo): String = info.fullName

  def refresh(userId: Id[User]): Future[PrefixFilter[SocialUserInfo]] = {
    build(userId).map { filter =>
      cache.set(SocialUserTypeaheadKey(userId), filter)
      store += (userId -> filter)
      log.info(s"[rebuild($userId)] cache/store updated; filter=$filter")
      filter
    }(ExecutionContext.fj)
  }

  def refreshAll(): Future[Unit] = {
    val userIds = db.readOnlyMaster { implicit ro =>
      userRepo.getAllActiveIds()
    }
    refreshByIds(userIds)
  }
}

trait SocialUserTypeaheadStore extends PrefixFilterStore[User, SocialUserInfo]

class S3SocialUserTypeaheadStore @Inject() (bucket: S3Bucket, amazonS3Client: AmazonS3, accessLog: AccessLog) extends S3PrefixFilterStoreImpl[User, SocialUserInfo](bucket, amazonS3Client, accessLog) with SocialUserTypeaheadStore

class InMemorySocialUserTypeaheadStoreImpl extends InMemoryPrefixFilterStoreImpl[User, SocialUserInfo] with SocialUserTypeaheadStore

class SocialUserTypeaheadCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends BinaryCacheImpl[SocialUserTypeaheadKey, PrefixFilter[SocialUserInfo]](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

case class SocialUserTypeaheadKey(userId: Id[User]) extends Key[PrefixFilter[SocialUserInfo]] {
  val namespace = "social_user_typeahead"
  override val version = 1
  def toKey(): String = userId.id.toString
}
