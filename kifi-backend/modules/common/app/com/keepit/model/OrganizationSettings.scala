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
) {
  def set[F <: Feature](kv: (F, FeatureSetting[F])): OrganizationSettings = kv match {
    case (Feature.PublishLibraries, s: FeatureSetting[Feature.PublishLibraries.type]) => this.copy(publishLibraries = s)
    case (Feature.InviteMembers, s: FeatureSetting[Feature.InviteMembers.type]) => this.copy(inviteMembers = s)
    case (Feature.MessageOrganization, s: FeatureSetting[Feature.MessageOrganization.type]) => this.copy(messageOrganization = s)
    case (Feature.ForceEditLibraries, s: FeatureSetting[Feature.ForceEditLibraries.type]) => this.copy(forceEditLibraries = s)
    case (Feature.ViewMembers, s: FeatureSetting[Feature.ViewMembers.type]) => this.copy(viewMembers = s)
    case (Feature.RemoveLibraries, s: FeatureSetting[Feature.RemoveLibraries.type]) => this.copy(removeLibraries = s)
    case (Feature.CreateSlackIntegration, s: FeatureSetting[Feature.CreateSlackIntegration.type]) => this.copy(createSlackIntegration = s)
    case (Feature.EditOrganization, s: FeatureSetting[Feature.EditOrganization.type]) => this.copy(editOrganization = s)
    case (Feature.ExportKeeps, s: FeatureSetting[Feature.ExportKeeps.type]) => this.copy(exportKeeps = s)
  }
}

object OrganizationSettings {
  implicit val publishLibrariesFormat = Feature.PublishLibrariesSetting.format
  implicit val inviteMembersFormat = Feature.InviteMembersSetting.format
  implicit val messageOrganizationFormat = Feature.MessageOrganizationSetting.format
  implicit val forceEditLibrariesFormat = Feature.ForceEditLibrariesSetting.format
  implicit val viewMembersFormat = Feature.ViewMembersSetting.format
  implicit val removeLibrariesFormat = Feature.RemoveLibrariesSetting.format
  implicit val createSlackIntegrationFormat = Feature.CreateSlackIntegrationSetting.format
  implicit val editOrganizationFormat = Feature.EditOrganizationSetting.format
  implicit val exportKeepsFormat = Feature.ExportKeepsSetting.format

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
  )(OrganizationSettings.apply, unlift(OrganizationSettings.unapply))
}

sealed trait Feature {
  def value: String
}
sealed abstract class OrganizationPermissionFeature(val permission: OrganizationPermission) extends Feature {
  val value = permission.value
}
sealed abstract class FeatureSetting[F <: Feature](val value: String)

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
  def apply(str: String): Feature = str match {
    case PublishLibraries.value => PublishLibraries
    case InviteMembers.value => InviteMembers
    case MessageOrganization.value => MessageOrganization
    case ForceEditLibraries.value => ForceEditLibraries
    case ViewMembers.value => ViewMembers
    case RemoveLibraries.value => RemoveLibraries
    case CreateSlackIntegration.value => CreateSlackIntegration
    case EditOrganization.value => EditOrganization
    case ExportKeeps.value => ExportKeeps
  }

  implicit val format: Format[Feature] = Format (
    __.read[String].map(Feature(_)),
    Writes { x => JsString(x.value) }
  )

  case object PublishLibraries extends OrganizationPermissionFeature(OrganizationPermission.PUBLISH_LIBRARIES)
  object PublishLibrariesSetting {
    val format: Format[FeatureSetting[PublishLibraries.type]] = Format(__.read[String].map(toSetting), Writes { x => JsString(x.value) } )
    def toSetting = Set(DISABLED, ADMINS, MEMBERS).map(x => x.value -> x).toMap.apply _
    case object DISABLED extends FeatureSetting[PublishLibraries.type]("disabled")
    case object ADMINS extends FeatureSetting[PublishLibraries.type]("admins")
    case object MEMBERS extends FeatureSetting[PublishLibraries.type]("members")
  }

  case object InviteMembers extends OrganizationPermissionFeature(OrganizationPermission.INVITE_MEMBERS)
  object InviteMembersSetting {
    val format: Format[FeatureSetting[InviteMembers.type]] = Format(__.read[String].map(toSetting), Writes { x => JsString(x.value) } )
    def toSetting = Set(DISABLED, ADMINS, MEMBERS).map(x => x.value -> x).toMap.apply _
    case object DISABLED extends FeatureSetting[InviteMembers.type]("disabled")
    case object ADMINS extends FeatureSetting[InviteMembers.type]("admins")
    case object MEMBERS extends FeatureSetting[InviteMembers.type]("members")
  }

  case object MessageOrganization extends OrganizationPermissionFeature(OrganizationPermission.MESSAGE_ORGANIZATION)
  object MessageOrganizationSetting {
    val format: Format[FeatureSetting[MessageOrganization.type]] = Format(__.read[String].map(toSetting), Writes { x => JsString(x.value) } )
    def toSetting = Set(DISABLED, ADMINS, MEMBERS).map(x => x.value -> x).toMap.apply _
    case object DISABLED extends FeatureSetting[MessageOrganization.type]("disabled")
    case object ADMINS extends FeatureSetting[MessageOrganization.type]("admins")
    case object MEMBERS extends FeatureSetting[MessageOrganization.type]("members")
  }

  case object ForceEditLibraries extends OrganizationPermissionFeature(OrganizationPermission.FORCE_EDIT_LIBRARIES)
  object ForceEditLibrariesSetting {
    val format: Format[FeatureSetting[ForceEditLibraries.type]] = Format(__.read[String].map(toSetting), Writes { x => JsString(x.value) } )
    def toSetting = Set(DISABLED, ADMINS, MEMBERS).map(x => x.value -> x).toMap.apply _
    case object DISABLED extends FeatureSetting[ForceEditLibraries.type]("disabled")
    case object ADMINS extends FeatureSetting[ForceEditLibraries.type]("admins")
    case object MEMBERS extends FeatureSetting[ForceEditLibraries.type]("members")
  }

  case object ViewMembers extends OrganizationPermissionFeature(OrganizationPermission.VIEW_MEMBERS)
  object ViewMembersSetting {
    val format: Format[FeatureSetting[ViewMembers.type]] = Format(__.read[String].map(toSetting), Writes { x => JsString(x.value) } )
    def toSetting = Set(DISABLED, ADMINS, MEMBERS, ANYONE).map(x => x.value -> x).toMap.apply _
    case object DISABLED extends FeatureSetting[ViewMembers.type]("disabled")
    case object ADMINS extends FeatureSetting[ViewMembers.type]("admins")
    case object MEMBERS extends FeatureSetting[ViewMembers.type]("members")
    case object ANYONE extends FeatureSetting[ViewMembers.type]("anyone")
  }

  case object RemoveLibraries extends OrganizationPermissionFeature(OrganizationPermission.REMOVE_LIBRARIES)
  object RemoveLibrariesSetting {
    val format: Format[FeatureSetting[RemoveLibraries.type]] = Format(__.read[String].map(toSetting), Writes { x => JsString(x.value) } )
    def toSetting = Set(DISABLED, ADMINS, MEMBERS).map(x => x.value -> x).toMap.apply _
    case object DISABLED extends FeatureSetting[RemoveLibraries.type]("disabled")
    case object ADMINS extends FeatureSetting[RemoveLibraries.type]("admins")
    case object MEMBERS extends FeatureSetting[RemoveLibraries.type]("members")
  }

  case object CreateSlackIntegration extends OrganizationPermissionFeature(OrganizationPermission.CREATE_SLACK_INTEGRATION)
  object CreateSlackIntegrationSetting {
    val format: Format[FeatureSetting[CreateSlackIntegration.type]] = Format(__.read[String].map(toSetting), Writes { x => JsString(x.value) } )
    def toSetting = Set(DISABLED, ADMINS, MEMBERS).map(x => x.value -> x).toMap.apply _
    case object DISABLED extends FeatureSetting[CreateSlackIntegration.type]("disabled")
    case object ADMINS extends FeatureSetting[CreateSlackIntegration.type]("admins")
    case object MEMBERS extends FeatureSetting[CreateSlackIntegration.type]("members")
  }

  case object EditOrganization extends OrganizationPermissionFeature(OrganizationPermission.EDIT_ORGANIZATION)
  object EditOrganizationSetting {
    val format: Format[FeatureSetting[EditOrganization.type]] = Format(__.read[String].map(toSetting), Writes { x => JsString(x.value) } )
    def toSetting = Set(DISABLED, ADMINS, MEMBERS).map(x => x.value -> x).toMap.apply _
    case object DISABLED extends FeatureSetting[EditOrganization.type]("disabled")
    case object ADMINS extends FeatureSetting[EditOrganization.type]("admins")
    case object MEMBERS extends FeatureSetting[EditOrganization.type]("members")
  }

  case object ExportKeeps extends OrganizationPermissionFeature(OrganizationPermission.EXPORT_KEEPS)
  object ExportKeepsSetting {
    val format: Format[FeatureSetting[ExportKeeps.type]] = Format(__.read[String].map(toSetting), Writes { x => JsString(x.value) } )
    def toSetting = Set(DISABLED, ADMINS, MEMBERS).map(x => x.value -> x).toMap.apply _
    case object DISABLED extends FeatureSetting[ExportKeeps.type]("disabled")
    case object ADMINS extends FeatureSetting[ExportKeeps.type]("admins")
    case object MEMBERS extends FeatureSetting[ExportKeeps.type]("members")
  }
}

