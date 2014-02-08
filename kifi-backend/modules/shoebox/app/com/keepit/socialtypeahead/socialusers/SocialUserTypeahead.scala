package com.keepit.socialtypeahead.socialusers

import com.keepit.common.cache.BinaryCacheImpl
import com.keepit.common.cache.CacheStatistics
import com.keepit.common.cache.FortyTwoCachePlugin
import com.keepit.common.cache.Key
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.AccessLog
import com.keepit.common.store.S3Bucket
import com.keepit.model.User
import com.keepit.model.SocialUserBasicInfo
import com.keepit.model.SocialUserInfo
import com.keepit.model.SocialUserInfoRepo
import com.keepit.serializer.ArrayBinaryFormat
import com.keepit.socialtypeahead.PrefixFilter
import com.keepit.socialtypeahead.S3PrefixFilterStoreImpl
import com.keepit.common.cache.TransactionalCaching.Implicits.directCacheAccess
import scala.concurrent.duration.Duration
import com.google.inject.Inject
import com.amazonaws.services.s3.AmazonS3
import com.keepit.socialtypeahead.Typeahead

class SocialUserTypeahead @Inject() (
  db: Database,
  typeaheadStore: SocialUserTypeaheadStore,
  typeaheadCache: SocialUserTypeaheadCache,
  socialUserNameCache: SocialUserNameCache,
  socialUserRepo: SocialUserInfoRepo
) extends Typeahead[SocialUserInfo, SocialUserBasicInfo] {

  override def getPrefixFilter(userId: Id[User]): Option[PrefixFilter[SocialUserInfo]] = {
    typeaheadCache.getOrElseOpt(SocialUserTypeaheadKey(userId)){ typeaheadStore.get(userId) }.map{ new PrefixFilter[SocialUserInfo](_) }
  }

  override def getInfos(ids: Seq[Id[SocialUserInfo]]): Seq[SocialUserBasicInfo] = {
    db.readOnly { implicit session =>
      socialUserRepo.getSocialUserBasicInfos(ids).valuesIterator.toSeq
    }
  }

  override def extractName(info: SocialUserBasicInfo): String = info.fullName
}

class SocialUserTypeaheadStore @Inject() (bucket: S3Bucket, amazonS3Client: AmazonS3, accessLog: AccessLog) extends S3PrefixFilterStoreImpl[User](bucket, amazonS3Client, accessLog)

class SocialUserTypeaheadCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration))
  extends BinaryCacheImpl[SocialUserTypeaheadKey, Array[Long]](stats, accessLog, innermostPluginSettings)(ArrayBinaryFormat.longArrayFormat)

case class SocialUserTypeaheadKey(userId: Id[User]) extends Key[Array[Long]] {
  val namespace = "social_user_typeahead"
  override val version = 1
  def toKey(): String = userId.id.toString
}
