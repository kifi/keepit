package com.keepit.model

import com.keepit.common.cache.{ JsonCacheImpl, FortyTwoCachePlugin, Key, CacheStatistics }
import com.keepit.common.logging.AccessLog
import com.keepit.common.db._
import com.keepit.common.time._
import org.joda.time.DateTime
import play.api.mvc.QueryStringBindable
import scala.concurrent.duration._
import com.keepit.common.json.TraversableFormat
import play.api.libs.json._

case class OrganizationExperiment(
    id: Option[Id[OrganizationExperiment]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[OrganizationExperiment] = OrganizationExperimentStates.ACTIVE,
    orgId: Id[Organization],
    experimentType: OrganizationExperimentType) extends ModelWithState[OrganizationExperiment] {
  def withId(id: Id[OrganizationExperiment]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def withState(state: State[OrganizationExperiment]) = this.copy(state = state)
  def isActive: Boolean = this.state == OrganizationExperimentStates.ACTIVE
}

object OrganizationExperimentStates extends States[OrganizationExperiment] {
  implicit val formatter = State.format[OrganizationExperimentType]
}

case class OrganizationExperimentOrganizationIdKey(orgId: Id[Organization]) extends Key[Seq[OrganizationExperimentType]] {
  override val version = 1
  val namespace = "org_experiment_org_id"
  def toKey(): String = orgId.id.toString
}

class OrganizationExperimentCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[OrganizationExperimentOrganizationIdKey, Seq[OrganizationExperimentType]](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)(TraversableFormat.seq[OrganizationExperimentType])

final case class OrganizationExperimentType(value: String) {
  override def toString = value
}

object OrganizationExperimentType {

  implicit val format: Format[OrganizationExperimentType] = Format(
    __.read[String].map(OrganizationExperimentType(_)),
    new Writes[OrganizationExperimentType] { def writes(o: OrganizationExperimentType) = JsString(o.value) }
  )

  implicit def queryStringBinder[T](implicit stringBinder: QueryStringBindable[String]) = new QueryStringBindable[OrganizationExperimentType] {
    override def bind(key: String, params: Map[String, Seq[String]]): Option[Either[String, OrganizationExperimentType]] = {
      stringBinder.bind(key, params) map {
        case Right(value) => Right(OrganizationExperimentType(value))
        case _ => Left("Unable to bind a ExperimentType")
      }
    }
    override def unbind(key: String, experimentType: OrganizationExperimentType): String = {
      stringBinder.unbind(key, experimentType.value)
    }
  }

  val FAKE = OrganizationExperimentType("fake")

  val _ALL = FAKE :: Nil

  private val _ALL_MAP: Map[String, OrganizationExperimentType] = _ALL.map(e => e.value -> e).toMap

  def get(str: String): OrganizationExperimentType = OrganizationExperimentType(str.toLowerCase.trim)
}
