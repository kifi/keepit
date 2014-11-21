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

case class SociallyRelatedEntities(
  users: RelatedEntities[User, User],
  facebookAccounts: RelatedEntities[User, SocialUserInfo],
  linkedInAccounts: RelatedEntities[User, SocialUserInfo],
  emailAccounts: RelatedEntities[User, EmailAccountInfo],
  createdAt: DateTime = currentDateTime)

object SociallyRelatedEntities {
  implicit val format = (
    (__ \ 'users).format[RelatedEntities[User, User]] and
    (__ \ 'facebookAccounts).format[RelatedEntities[User, SocialUserInfo]] and
    (__ \ 'linkedInAccounts).format[RelatedEntities[User, SocialUserInfo]] and
    (__ \ 'emailAccounts).format[RelatedEntities[User, EmailAccountInfo]] and
    OFormat((__ \ 'createdAt).read[DateTime] orElse (__ \ 'timestamp).read[DateTime], (__ \ 'createdAt).write[DateTime])
  )(SociallyRelatedEntities.apply _, unlift(SociallyRelatedEntities.unapply))

  def empty(userId: Id[User]): SociallyRelatedEntities = SociallyRelatedEntities(RelatedEntities.empty(userId), RelatedEntities.empty(userId), RelatedEntities.empty(userId), RelatedEntities.empty(userId))
}

case class SociallyRelatedEntitiesCacheKey(id: Id[User]) extends Key[SociallyRelatedEntities] {
  override val version = 3
  val namespace = "socially_related_entities"
  def toKey(): String = id.id.toString
}

class SociallyRelatedEntitiesCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[SociallyRelatedEntitiesCacheKey, SociallyRelatedEntities](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)
