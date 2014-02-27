package com.keepit.typeahead.socialusers

import com.keepit.common.cache.BinaryCacheImpl
import com.keepit.common.cache.CacheStatistics
import com.keepit.common.cache.FortyTwoCachePlugin
import com.keepit.common.cache.Key
import com.keepit.common.cache.TransactionalCaching.Implicits.directCacheAccess
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.{Logging, AccessLog}
import com.keepit.model._
import com.keepit.serializer.ArrayBinaryFormat
import com.keepit.typeahead._
import scala.concurrent.duration.Duration
import com.google.inject.Inject
import com.amazonaws.services.s3.AmazonS3
import com.keepit.common.store.{InMemoryObjectStore, S3Bucket}
import scala.concurrent.{Await, Future}
import com.keepit.common.akka.SafeFuture
import scala.collection.mutable
import com.keepit.typeahead
import scala.collection.mutable.ArrayBuffer
import com.keepit.common.concurrent.ExecutionContext
import com.keepit.common.healthcheck.AirbrakeNotifier

class SocialUserTypeahead @Inject() (
  db: Database,
  override val airbrake: AirbrakeNotifier,
  userRepo: UserRepo,
  store: SocialUserTypeaheadStore,
  cache: SocialUserTypeaheadCache,
  socialConnRepo:SocialConnectionRepo,
  socialUserRepo: SocialUserInfoRepo
) extends Typeahead[SocialUserInfo, SocialUserBasicInfo] with Logging {

  protected def asyncGetOrCreatePrefixFilter(userId: Id[User]): Future[PrefixFilter[SocialUserInfo]] = {
    cache.getOrElseFuture(SocialUserTypeaheadKey(userId)) {
      store.get(userId) match {
        case Some(filter) => Future.successful(filter)
        case None =>
          build(userId).map{ filter =>
            store += (userId -> filter.data)
            filter.data
          }(ExecutionContext.fj)
      }
    }.map{ new PrefixFilter[SocialUserInfo](_) }(ExecutionContext.fj)
  }

  override protected def getInfos(ids: Seq[Id[SocialUserInfo]]): Seq[SocialUserBasicInfo] = {
    if (ids.isEmpty) Seq.empty[SocialUserBasicInfo]
    else {
      db.readOnly { implicit session =>
        socialUserRepo.getSocialUserBasicInfos(ids).valuesIterator.toVector // do NOT use toSeq (=> toStream (lazy))
      }
    }
  }

  override protected def getAllInfosForUser(id: Id[User]): Seq[SocialUserBasicInfo] = {
    val builder = new mutable.ArrayBuffer[SocialUserBasicInfo]
    db.readOnly { implicit session =>
      val infos = socialUserRepo.getSocialUserBasicInfosByUser(id) // todo: filter out fortytwo?
      log.info(s"[social.getAllInfosForUser($id)] res(len=${infos.length}):${infos.mkString(",")}")
      for (info <- infos) {
        val connInfos = socialConnRepo.getSocialUserConnections(info.id)
        log.info(s"[social.getConns($id)] (${info.id},${info.networkType}).conns(len=${connInfos.length}):${connInfos.mkString(",")}")
        builder ++= connInfos.map { SocialUserBasicInfo.fromSocialUser(_) } // conversion overhead
      }
    }
    val res = builder.result
    log.info(s"[social.getAllInfosForUser($id)] res(len=${res.length}): ${res.mkString(",")}")
    res
  }

  override protected def extractId(info: SocialUserBasicInfo): Id[SocialUserInfo] = info.id

  override protected def extractName(info: SocialUserBasicInfo): String = info.fullName

  def refresh(userId: Id[User]): Future[Unit] = {
    build(userId).map{ filter =>
      store += (userId -> filter.data)
      cache.set(SocialUserTypeaheadKey(userId), filter.data)
      log.info(s"[refresh($userId)] cache updated; filter=$filter")
    }(ExecutionContext.fj)
  }

  def refreshByIds(ids: Seq[Id[User]]): Future[Unit] = {
    val futures = new ArrayBuffer[Future[Unit]]
    for (id <- ids) {
      futures += refresh(id)
    }
    implicit val fj = ExecutionContext.fj
    Future.sequence(futures) map { seq => Unit }
  }

  def refreshAll(): Future[Unit] = {
    val userIds = db.readOnly { implicit ro =>
      userRepo.getAllActiveIds()
    }
    refreshByIds(userIds)
  }
}

trait SocialUserTypeaheadStore extends PrefixFilterStore[User]

class S3SocialUserTypeaheadStore @Inject() (bucket: S3Bucket, amazonS3Client: AmazonS3, accessLog: AccessLog) extends S3PrefixFilterStoreImpl[User](bucket, amazonS3Client, accessLog) with SocialUserTypeaheadStore

class InMemorySocialUserTypeaheadStoreImpl extends InMemoryPrefixFilterStoreImpl[User] with SocialUserTypeaheadStore

class SocialUserTypeaheadCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends BinaryCacheImpl[SocialUserTypeaheadKey, Array[Long]](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings:_*)(ArrayBinaryFormat.longArrayFormat)

case class SocialUserTypeaheadKey(userId: Id[User]) extends Key[Array[Long]] {
  val namespace = "social_user_typeahead"
  override val version = 1
  def toKey(): String = userId.id.toString
}
