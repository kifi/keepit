package com.keepit.model

import com.keepit.common.crypto.PublicId
import com.keepit.common.db._
import com.keepit.common.time._
import org.joda.time.DateTime
import play.api.libs.json.{ Json, Writes }

case class OrganizationConfiguration(
    id: Option[Id[OrganizationConfiguration]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[OrganizationConfiguration] = OrganizationConfigurationStates.ACTIVE,
    organizationId: Id[Organization],
    settings: OrganizationSettings) extends ModelWithState[OrganizationConfiguration] {

  def withId(id: Id[OrganizationConfiguration]): OrganizationConfiguration = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime): OrganizationConfiguration = this.copy(updatedAt = now)
  def withSettings(newSettings: OrganizationSettings): OrganizationConfiguration = this.copy(settings = newSettings)
  def sanitizeForDelete: OrganizationConfiguration = this.copy(
    state = OrganizationConfigurationStates.INACTIVE
  )
}

object OrganizationConfigurationStates extends States[OrganizationConfiguration]

case class ExternalOrganizationConfiguration(
  organizationId: PublicId[Organization],
  settings: OrganizationSettings)

object ExternalOrganizationConfiguration {
  implicit val writes: Writes[ExternalOrganizationConfiguration] = Writes { config =>
    Json.obj(
      "id" -> config.organizationId,
      "settings" -> config.settings
    )
  }
}

