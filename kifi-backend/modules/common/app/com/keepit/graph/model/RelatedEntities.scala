package com.keepit.graph.model

import com.keepit.common.db.Id
import org.joda.time.DateTime
import play.api.libs.json._
import play.api.libs.functional.syntax._
import com.keepit.common.cache.{ Key, JsonCacheImpl, FortyTwoCachePlugin, CacheStatistics }
import com.keepit.common.logging.AccessLog
import com.keepit.common.time._
import scala.concurrent.duration.Duration
import com.keepit.model.{ Organization, SocialUserInfo, User }
import com.keepit.abook.model.EmailAccountInfo

case class RelatedEntities[E, R](id: Id[E], related: Seq[(Id[R], Double)])

object RelatedEntities {
  import com.keepit.common.json.TupleFormat._
  implicit def format[E, R] = (
    (__ \ 'id).format[Id[E]] and
    (__ \ 'related).format[Seq[(Id[R], Double)]]
  )(RelatedEntities.apply, unlift(RelatedEntities.unapply))

  def top[E, R](id: Id[E], related: Seq[(Id[R], Double)], limit: Int) = RelatedEntities(id, related.sortBy(-_._2).take(limit))

  def empty[E, R](id: Id[E]) = RelatedEntities(id, Seq.empty[(Id[R], Double)])
}

case class SociallyRelatedEntitiesForUser(
  users: RelatedEntities[User, User],
  facebookAccounts: RelatedEntities[User, SocialUserInfo],
  linkedInAccounts: RelatedEntities[User, SocialUserInfo],
  emailAccounts: RelatedEntities[User, EmailAccountInfo],
  createdAt: DateTime = currentDateTime)

object SociallyRelatedEntitiesForUser {

  implicit def format: Format[SociallyRelatedEntitiesForUser] = (
    (__ \ 'users).format[RelatedEntities[User, User]] and
    (__ \ 'facebookAccounts).format[RelatedEntities[User, SocialUserInfo]] and
    (__ \ 'linkedInAccounts).format[RelatedEntities[User, SocialUserInfo]] and
    (__ \ 'emailAccounts).format[RelatedEntities[User, EmailAccountInfo]] and
    (__ \ 'createdAt).format[DateTime]
  )(SociallyRelatedEntitiesForUser.apply _, unlift(SociallyRelatedEntitiesForUser.unapply))

  def empty(userId: Id[User]): SociallyRelatedEntitiesForUser = SociallyRelatedEntitiesForUser(RelatedEntities.empty(userId), RelatedEntities.empty(userId), RelatedEntities.empty(userId), RelatedEntities.empty(userId))
}

case class SociallyRelatedEntitiesForOrg(
  users: RelatedEntities[Organization, User],
  emailAccounts: RelatedEntities[Organization, EmailAccountInfo],
  createdAt: DateTime = currentDateTime)

object SociallyRelatedEntitiesForOrg {

  implicit def format: Format[SociallyRelatedEntitiesForOrg] = (
    (__ \ 'users).format[RelatedEntities[Organization, User]] and
    (__ \ 'emailAccounts).format[RelatedEntities[Organization, EmailAccountInfo]] and
    (__ \ 'createdAt).format[DateTime]
  )(SociallyRelatedEntitiesForOrg.apply _, unlift(SociallyRelatedEntitiesForOrg.unapply))

  def empty(orgId: Id[Organization]): SociallyRelatedEntitiesForOrg = SociallyRelatedEntitiesForOrg(RelatedEntities.empty(orgId), RelatedEntities.empty(orgId))
}

case class SociallyRelatedEntitiesForUserCacheKey(id: Id[User]) extends Key[SociallyRelatedEntitiesForUser] {
  override val version = 4
  val namespace = "user_socially_related_entities" // does refactoring this require any other actions?
  def toKey(): String = id.id.toString
}

class SociallyRelatedEntitiesForUserCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[SociallyRelatedEntitiesForUserCacheKey, SociallyRelatedEntitiesForUser](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

case class SociallyRelatedEntitiesForOrgCacheKey(id: Id[Organization]) extends Key[SociallyRelatedEntitiesForOrg] {
  override val version = 1
  val namespace = "org_socially_related_entities"
  def toKey(): String = id.id.toString
}

class SociallyRelatedEntitiesForOrgCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[SociallyRelatedEntitiesForOrgCacheKey, SociallyRelatedEntitiesForOrg](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)
