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
import scala.concurrent.Future
import com.keepit.common.akka.SafeFuture
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.collection.mutable

class SocialUserTypeahead @Inject() (
  db: Database,
  override val store: SocialUserTypeaheadStore,
  cache: SocialUserTypeaheadCache,
  socialConnRepo:SocialConnectionRepo,
  socialUserBasicInfoCache: SocialUserBasicInfoCache,
  socialUserRepo: SocialUserInfoRepo
) extends Typeahead[SocialUserInfo, SocialUserBasicInfo] with Logging {

  override def getPrefixFilter(userId: Id[User]): Option[PrefixFilter[SocialUserInfo]] = {
    cache.getOrElseOpt(SocialUserTypeaheadKey(userId)){ store.get(userId) }.map{ new PrefixFilter[SocialUserInfo](_) }
  }

  override protected def getInfos(ids: Seq[Id[SocialUserInfo]]): Seq[SocialUserBasicInfo] = {
    db.readOnly { implicit session =>
      socialUserRepo.getSocialUserBasicInfos(ids).valuesIterator.toSeq
    }
  }

  override protected def getAllInfosForUser(id: Id[User]): Seq[SocialUserBasicInfo] = {
    val builder = mutable.ArrayBuilder.make[SocialUserBasicInfo]
    db.readOnly { implicit session =>
      val infos = socialUserRepo.getSocialUserBasicInfosByUser(id)
      log.info(s"[getAllInfosForUser($id)] res=${infos.mkString(",")}")
      for (info <- infos) {
        val connInfos = socialConnRepo.getSocialUserConnections(info.id)
        log.info(s"[getConns($id)] (${info.id},${info.networkType}).conns=(${connInfos.mkString(",")})")
        builder ++= connInfos.map { SocialUserBasicInfo.fromSocialUser(_) } // conversion overhead
      }
    }
    val res = builder.result
    log.info(s"[getAllInfosForUser($id)] res(len=${res.length}): ${res.mkString(",")}")
    res.toSeq
  }

  override protected def extractId(info: SocialUserBasicInfo): Id[SocialUserInfo] = info.id

  override protected def extractName(info: SocialUserBasicInfo): String = info.fullName
}

trait SocialUserTypeaheadStore extends PrefixFilterStore[User]

class S3SocialUserTypeaheadStore @Inject() (bucket: S3Bucket, amazonS3Client: AmazonS3, accessLog: AccessLog) extends S3PrefixFilterStoreImpl[User](bucket, amazonS3Client, accessLog) with SocialUserTypeaheadStore

class InMemorySocialUserTypeaheadStoreImpl extends InMemoryPrefixFilterStoreImpl[User] with SocialUserTypeaheadStore

class SocialUserTypeaheadCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration))
  extends BinaryCacheImpl[SocialUserTypeaheadKey, Array[Long]](stats, accessLog, innermostPluginSettings)(ArrayBinaryFormat.longArrayFormat)

case class SocialUserTypeaheadKey(userId: Id[User]) extends Key[Array[Long]] {
  val namespace = "social_user_typeahead"
  override val version = 1
  def toKey(): String = userId.id.toString
}
