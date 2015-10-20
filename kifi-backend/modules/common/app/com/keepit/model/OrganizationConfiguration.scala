package com.keepit.model

import com.keepit.common.db._
import com.keepit.common.time._
import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._

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
  showUpsells: Boolean,
  settings: OrganizationSettingsWithEditability)

object ExternalOrganizationConfiguration {
  implicit val writes = (
    (__ \ 'showUpsells).write[Boolean] and
    (__ \ 'settings).write[OrganizationSettingsWithEditability]
  )(unlift(ExternalOrganizationConfiguration.unapply))
}

