package com.keepit.model

import com.keepit.common.db._
import com.keepit.common.time._
import org.joda.time.DateTime
import com.keepit.common.cache._
import com.keepit.common.logging.AccessLog
import scala.concurrent.duration._
import play.api.libs.json._
import play.api.libs.functional.syntax._

case class UrlPatternRule(
    id: Option[Id[UrlPatternRule]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[UrlPatternRule] = UrlPatternRuleStates.ACTIVE,
    pattern: String,
    example: Option[String] = None,
    isUnscrapable: Boolean = false,
    useProxy: Option[Id[HttpProxy]] = None,
    normalization: Option[Normalization] = None,
    trustedDomain: Option[String] = None,
    nonSensitive: Boolean = false) extends Model[UrlPatternRule] {

  def withId(id: Id[UrlPatternRule]) = this.copy(id = Some(id))
  def withState(newState: State[UrlPatternRule]) = this.copy(state = newState)
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def isActive = this.state == UrlPatternRuleStates.ACTIVE

}

object UrlPatternRule {
  implicit def format = (
    (__ \ 'id).formatNullable(Id.format[UrlPatternRule]) and
    (__ \ 'createdAt).format(DateTimeJsonFormat) and
    (__ \ 'updatedAt).format(DateTimeJsonFormat) and
    (__ \ 'state).format(State.format[UrlPatternRule]) and
    (__ \ 'pattern).format[String] and
    (__ \ 'example).formatNullable[String] and
    (__ \ 'isUnscrapable).format[Boolean] and
    (__ \ 'useProxy).formatNullable(Id.format[HttpProxy]) and
    (__ \ 'normalization).formatNullable[Normalization] and
    (__ \ 'trustedDomain).formatNullable[String] and
    (__ \ 'nonSensitive).format[Boolean]
  )(UrlPatternRule.apply, unlift(UrlPatternRule.unapply))
}

case class UrlPatternRuleAllKey() extends Key[Seq[UrlPatternRule]] {
  override val version = 4
  val namespace = "url_pattern_rule_all"
  def toKey(): String = "all"
}

class UrlPatternRuleAllCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[UrlPatternRuleAllKey, Seq[UrlPatternRule]](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

object UrlPatternRuleStates extends States[UrlPatternRule]
