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
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.collection.mutable
import com.keepit.typeahead

class SocialUserTypeahead @Inject() (
  db: Database,
  override val store: SocialUserTypeaheadStore,
  cache: SocialUserTypeaheadCache,
  socialConnRepo:SocialConnectionRepo,
  socialUserRepo: SocialUserInfoRepo
) extends Typeahead[SocialUserInfo, SocialUserBasicInfo] with Logging {

  override def getPrefixFilter(userId: Id[User]): Option[PrefixFilter[SocialUserInfo]] = {
    cache.get(SocialUserTypeaheadKey(userId)) match { // todo(ray): log.debug
      case Some(filter) =>
        log.info(s"[getPrefixFilter($userId)] CACHE.filter(len=${filter.length})")
        Some(new PrefixFilter[SocialUserInfo](filter))
      case None =>
        log.info(s"[getPrefixFilter($userId)] NO FILTER in cache; check store")
        val filter = store.get(userId) match {
          case Some(filter) =>
            log.info(s"[getPrefixFilter($userId)] STORE.filter(len=${filter.length})")
            new PrefixFilter[SocialUserInfo](filter)
          case None =>
            log.info(s"[getPrefixFilter($userId)] NO FILTER in store; BUILD")
            val pFilter = Await.result(build(userId), Duration.Inf)
            log.info(s"[getPrefixFilter($userId)] BUILT filter.len=${pFilter.data.length}")
            store += (userId -> pFilter.data)
            pFilter
        }
        cache.set(SocialUserTypeaheadKey(userId), filter.data)
        Some(filter)
    }
//    cache.getOrElseOpt(SocialUserTypeaheadKey(userId)){ store.get(userId) }.map{ new PrefixFilter[SocialUserInfo](_) }
  }

  override protected def getInfos(ids: Seq[Id[SocialUserInfo]]): Seq[SocialUserBasicInfo] = {
    db.readOnly { implicit session =>
      socialUserRepo.getSocialUserBasicInfos(ids).valuesIterator.toVector // do NOT use toSeq (=> toStream (lazy))
    }
  }

  override protected def getAllInfosForUser(id: Id[User]): Seq[SocialUserBasicInfo] = {
    val builder = new mutable.ArrayBuffer[SocialUserBasicInfo]
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
    res
  }

  override protected def extractId(info: SocialUserBasicInfo): Id[SocialUserInfo] = info.id

  override protected def extractName(info: SocialUserBasicInfo): String = info.fullName
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
