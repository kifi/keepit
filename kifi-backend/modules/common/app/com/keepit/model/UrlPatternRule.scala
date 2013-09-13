package com.keepit.model

import com.keepit.common.db._
import com.keepit.common.time._
import org.joda.time.DateTime
import com.keepit.common.cache._
import scala.concurrent.duration._
import play.api.libs.json._
import play.api.libs.functional.syntax._

case class UrlPatternRule(
  id: Option[Id[UrlPatternRule]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  state: State[UrlPatternRule] = UrlPatternRuleStates.ACTIVE,
  pattern: String,
  isUnscrapable: Boolean = false,
  normalization: Option[Normalization] = None,
  trustedDomain: Option[String] = None
) extends Model[UrlPatternRule] {

  def withId(id: Id[UrlPatternRule]) = this.copy(id = Some(id))
  def withState(newState: State[UrlPatternRule]) = this.copy(state = newState)
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)

}

object UrlPatternRule {
  implicit def format = (
    (__ \ 'id).formatNullable(Id.format[UrlPatternRule]) and
      (__ \ 'createdAt).format(DateTimeJsonFormat) and
      (__ \ 'updatedAt).format(DateTimeJsonFormat) and
      (__ \ 'state).format(State.format[UrlPatternRule]) and
      (__ \ 'pattern).format[String] and
      (__ \ 'isUnscrapable).format[Boolean] and
      (__ \ 'normalization).formatNullable[Normalization] and
      (__ \ 'trustedDomain).formatNullable[String]
    )(UrlPatternRule.apply, unlift(UrlPatternRule.unapply))
}

case class UrlPatternRuleAllKey() extends Key[Seq[UrlPatternRule]] {
  override val version = 1
  val namespace = "static_rule_all"
  def toKey(): String = "all"
}

class UrlPatternRuleAllCache(innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[UrlPatternRuleAllKey, Seq[UrlPatternRule]](innermostPluginSettings, innerToOuterPluginSettings:_*)

object UrlPatternRuleStates extends States[UrlPatternRule]
