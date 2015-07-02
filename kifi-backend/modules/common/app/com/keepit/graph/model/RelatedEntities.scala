package com.keepit.graph.model

import com.keepit.common.db.Id
import org.joda.time.DateTime
import play.api.libs.json._
import play.api.libs.functional.syntax._
import com.keepit.common.cache.{ Key, JsonCacheImpl, FortyTwoCachePlugin, CacheStatistics }
import com.keepit.common.logging.AccessLog
import com.keepit.common.time._
import scala.concurrent.duration.Duration
import com.keepit.model.{ SocialUserInfo, User }
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

case class SociallyRelatedEntities[E](
  users: RelatedEntities[E, User],
  facebookAccounts: RelatedEntities[E, SocialUserInfo],
  linkedInAccounts: RelatedEntities[E, SocialUserInfo],
  emailAccounts: RelatedEntities[E, EmailAccountInfo],
  createdAt: DateTime = currentDateTime)

object SociallyRelatedEntities {

  implicit def format[E]: Format[SociallyRelatedEntities[E]] = (
    (__ \ 'users).format[RelatedEntities[E, User]] and
    (__ \ 'facebookAccounts).format[RelatedEntities[E, SocialUserInfo]] and
    (__ \ 'linkedInAccounts).format[RelatedEntities[E, SocialUserInfo]] and
    (__ \ 'emailAccounts).format[RelatedEntities[E, EmailAccountInfo]] and
    (__ \ 'createdAt).format[DateTime]
  )(SociallyRelatedEntities.apply[E] _, unlift(SociallyRelatedEntities.unapply[E]))

  def empty[E](sourceId: Id[E]): SociallyRelatedEntities[E] = SociallyRelatedEntities[E](RelatedEntities.empty(sourceId), RelatedEntities.empty(sourceId), RelatedEntities.empty(sourceId), RelatedEntities.empty(sourceId))
}

case class SociallyRelatedEntitiesForUserCacheKey(id: Id[User]) extends Key[SociallyRelatedEntities[User]] {
  override val version = 3
  val namespace = "socially_related_entities"
  def toKey(): String = id.id.toString
}

class SociallyRelatedEntitiesForUserCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[SociallyRelatedEntitiesForUserCacheKey, SociallyRelatedEntities[User]](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)
