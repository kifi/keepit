package com.keepit.graph.model

import com.keepit.common.db.Id
import play.api.libs.json._
import play.api.libs.functional.syntax._
import com.keepit.common.cache.{ Key, JsonCacheImpl, FortyTwoCachePlugin, CacheStatistics }
import com.keepit.common.logging.AccessLog
import scala.concurrent.duration.Duration
import com.keepit.model.{ SocialUserInfo, User }
import com.keepit.abook.model.EmailAccountInfo

case class RelatedEntities[E, R](id: Id[E], related: Seq[(Id[R], Double)])

object RelatedEntities {
  import com.keepit.common.json._
  implicit def format[E, R] = (
    (__ \ 'id).format[Id[E]] and
    (__ \ 'related).format[Seq[(Id[R], Double)]]
  )(RelatedEntities.apply, unlift(RelatedEntities.unapply))

  def top[E, R](id: Id[E], related: Seq[(Id[R], Double)], limit: Int) = RelatedEntities(id, related.sortBy(-_._2).take(limit))
}

case class SociallyRelatedUsersCacheKey(id: Id[User]) extends Key[RelatedEntities[User, User]] {
  override val version = 1
  val namespace = "socially_related_users"
  def toKey(): String = id.id.toString
}

class SociallyRelatedUsersCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[SociallyRelatedUsersCacheKey, RelatedEntities[User, User]](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

case class SociallyRelatedLinkedInAccountsCacheKey(id: Id[User]) extends Key[RelatedEntities[User, SocialUserInfo]] {
  override val version = 1
  val namespace = "socially_related_linkedin_accounts"
  def toKey(): String = id.id.toString
}

class SociallyRelatedLinkedInAccountsCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[SociallyRelatedLinkedInAccountsCacheKey, RelatedEntities[User, SocialUserInfo]](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

case class SociallyRelatedFacebookAccountsCacheKey(id: Id[User]) extends Key[RelatedEntities[User, SocialUserInfo]] {
  override val version = 1
  val namespace = "socially_related_facebook_accounts"
  def toKey(): String = id.id.toString
}

class SociallyRelatedFacebookAccountsCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[SociallyRelatedFacebookAccountsCacheKey, RelatedEntities[User, SocialUserInfo]](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

case class SociallyRelatedEmailAccountsCacheKey(id: Id[User]) extends Key[RelatedEntities[User, EmailAccountInfo]] {
  override val version = 1
  val namespace = "socially_related_email_accounts"
  def toKey(): String = id.id.toString
}

class SociallyRelatedEmailAccountsCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[SociallyRelatedEmailAccountsCacheKey, RelatedEntities[User, EmailAccountInfo]](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)
