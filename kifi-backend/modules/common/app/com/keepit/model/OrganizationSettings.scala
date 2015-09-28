package com.keepit.model

import play.api.libs.functional.syntax._
import play.api.libs.json._

case class OrganizationSettings(
  publishLibraries: FeatureSetting[Feature.PublishLibraries.type],
  inviteMembers: FeatureSetting[Feature.InviteMembers.type],
  messageOrganization: FeatureSetting[Feature.MessageOrganization.type],
  forceEditLibraries: FeatureSetting[Feature.ForceEditLibraries.type],
  viewMembers: FeatureSetting[Feature.ViewMembers.type],
  removeLibraries: FeatureSetting[Feature.RemoveLibraries.type],
  createSlackIntegration: FeatureSetting[Feature.CreateSlackIntegration.type],
  editOrganization: FeatureSetting[Feature.EditOrganization.type],
  exportKeeps: FeatureSetting[Feature.ExportKeeps.type]
)

/*
object OrganizationSettings {
  implicit val format: Format[OrganizationSettings] = (
    (__ \ 'publishLibraries).format[FeatureSetting[Feature.PublishLibraries.type]] and
    (__ \ 'inviteMembers).format[FeatureSetting[Feature.InviteMembers.type]] and
    (__ \ 'messageOrganization).format[FeatureSetting[Feature.MessageOrganization.type]] and
    (__ \ 'forceEditLibraries).format[FeatureSetting[Feature.ForceEditLibraries.type]] and
    (__ \ 'viewMembers).format[FeatureSetting[Feature.ViewMembers.type]] and
    (__ \ 'removeLibraries).format[FeatureSetting[Feature.RemoveLibraries.type]] and
    (__ \ 'createSlackIntegration).format[FeatureSetting[Feature.CreateSlackIntegration.type]] and
    (__ \ 'editOrganization).format[FeatureSetting[Feature.EditOrganization.type]] and
    (__ \ 'exportKeeps).format[FeatureSetting[Feature.ExportKeeps.type]]
  )(OrganizationSettings.apply _, unlift(OrganizationSettings.unapply))
}
*/

sealed trait Feature {
  def name: Name[Feature]
}
sealed abstract class OrganizationPermissionFeature(val permission: OrganizationPermission) extends Feature {
  def name = Name[Feature](permission.value)
}
sealed abstract class FeatureSetting[F <: Feature](val value: String)
object FeatureSetting {
  def apply(value: String): FeatureSetting[Feature.PublishLibraries.type] = value match {
    case Feature.PublishLibraries.DISABLED.value => Feature.PublishLibraries.DISABLED
    case Feature.PublishLibraries.ADMINS.value => Feature.PublishLibraries.ADMINS
    case Feature.PublishLibraries.MEMBERS.value => Feature.PublishLibraries.MEMBERS
  }
}

object Feature {
  val ALL: Set[Feature] = Set(
    PublishLibraries,
    InviteMembers,
    MessageOrganization,
    ForceEditLibraries,
    ViewMembers,
    RemoveLibraries,
    CreateSlackIntegration,
    EditOrganization,
    ExportKeeps
  )

  case object PublishLibraries extends OrganizationPermissionFeature(OrganizationPermission.PUBLISH_LIBRARIES) {
    case object DISABLED extends FeatureSetting[PublishLibraries.type]("disabled")
    case object ADMINS extends FeatureSetting[PublishLibraries.type]("admins")
    case object MEMBERS extends FeatureSetting[PublishLibraries.type]("members")
  }

  case object InviteMembers extends OrganizationPermissionFeature(OrganizationPermission.INVITE_MEMBERS) {
    case object DISABLED extends FeatureSetting[InviteMembers.type]("disabled")
    case object ADMINS extends FeatureSetting[InviteMembers.type]("admins")
    case object MEMBERS extends FeatureSetting[InviteMembers.type]("members")
  }

  case object MessageOrganization extends OrganizationPermissionFeature(OrganizationPermission.GROUP_MESSAGING) {
    case object DISABLED extends FeatureSetting[MessageOrganization.type]("disabled")
    case object ADMINS extends FeatureSetting[MessageOrganization.type]("admins")
    case object MEMBERS extends FeatureSetting[MessageOrganization.type]("members")
  }

  case object ForceEditLibraries extends OrganizationPermissionFeature(OrganizationPermission.FORCE_EDIT_LIBRARIES) {
    case object DISABLED extends FeatureSetting[ForceEditLibraries.type]("disabled")
    case object ADMINS extends FeatureSetting[ForceEditLibraries.type]("admins")
    case object MEMBERS extends FeatureSetting[ForceEditLibraries.type]("members")
  }

  case object ViewMembers extends OrganizationPermissionFeature(OrganizationPermission.VIEW_MEMBERS) {
    case object DISABLED extends FeatureSetting[ViewMembers.type]("disabled")
    case object ADMINS extends FeatureSetting[ViewMembers.type]("admins")
    case object MEMBERS extends FeatureSetting[ViewMembers.type]("members")
    case object ANYONE extends FeatureSetting[ViewMembers.type]("anyone")
  }

  case object RemoveLibraries extends OrganizationPermissionFeature(OrganizationPermission.REMOVE_LIBRARIES) {
    case object DISABLED extends FeatureSetting[RemoveLibraries.type]("disabled")
    case object ADMINS extends FeatureSetting[RemoveLibraries.type]("admins")
    case object MEMBERS extends FeatureSetting[RemoveLibraries.type]("members")
  }

  case object CreateSlackIntegration extends OrganizationPermissionFeature(OrganizationPermission.CREATE_SLACK_INTEGRATION) {
    case object DISABLED extends FeatureSetting[CreateSlackIntegration.type]("disabled")
    case object ADMINS extends FeatureSetting[CreateSlackIntegration.type]("admins")
    case object MEMBERS extends FeatureSetting[CreateSlackIntegration.type]("members")
  }

  case object EditOrganization extends OrganizationPermissionFeature(OrganizationPermission.EDIT_ORGANIZATION) {
    case object DISABLED extends FeatureSetting[EditOrganization.type]("disabled")
    case object ADMINS extends FeatureSetting[EditOrganization.type]("admins")
    case object MEMBERS extends FeatureSetting[EditOrganization.type]("members")
  }

  case object ExportKeeps extends OrganizationPermissionFeature(OrganizationPermission.EXPORT_KEEPS) {
    case object DISABLED extends FeatureSetting[ExportKeeps.type]("disabled")
    case object ADMINS extends FeatureSetting[ExportKeeps.type]("admins")
    case object MEMBERS extends FeatureSetting[ExportKeeps.type]("members")
  }
}

