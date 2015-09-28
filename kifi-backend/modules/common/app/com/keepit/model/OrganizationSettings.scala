package com.keepit.model

import play.api.libs.json._

case class OrganizationSettings(kvs: Set[FeatureSettingPair[_ <: FeatureKind]]) {
  def setAll(newKvs: Set[FeatureSettingPair[_ <: FeatureKind]]): OrganizationSettings = {
    val newFeatures = newKvs.map(_.feature)
    this.copy(kvs = kvs.filter(kv => !newFeatures.contains(kv.feature)) ++ newKvs)
  }

  def set[K <: FeatureKind](newKvs: (Feature[K], FeatureSetting[K])*): OrganizationSettings = setAll(newKvs.map(kv => FeatureSettingPair(kv._1, kv._2)).toSet)

  def extraPermissionsFor(roleOpt: Option[OrganizationRole]): Set[OrganizationPermission] = kvs.collect {
    case FeatureSettingPair(feature: PermissionFeature, setting: PermissionFeatureSetting) if setting.roles.contains(roleOpt) => feature.permission
  }

  override def toString: String = Json.toJson(this).toString()
}

object OrganizationSettings {
  val empty = OrganizationSettings(Set.empty)
  implicit val format: Format[OrganizationSettings] = Format (
    Reads { jsv =>
      jsv.validate[JsObject].map { obj =>
        OrganizationSettings(obj.fieldSet.map { case (f, s) =>
          Feature(f).makePair(s)
        }.toSet)
      }
    },
    Writes { orgSettings =>
      Json.toJson(orgSettings.kvs.map(fsp => fsp.feature.value -> fsp.setting.value).toMap)
    }
  )
}

sealed abstract class FeatureKind
object FeatureKind {
  case object PermissionKind extends FeatureKind
  type Permission = PermissionKind.type
}


sealed trait Feature[K <: FeatureKind] { self =>
  val value: String
  def settings: Set[FeatureSetting[K]]

  private def toSetting(x: String): Option[FeatureSetting[K]] = settings.find(_.value == x)
  def settingReads: Reads[FeatureSetting[K]] = __.read[String].map(x => toSetting(x).get)

  def makePair(js: JsValue): FeatureSettingPair[K] = FeatureSettingPair(this, js.as(settingReads))
}
sealed abstract class PermissionFeature(val permission: OrganizationPermission) extends Feature[FeatureKind.Permission] {
  val value = permission.value
}

sealed abstract class FeatureSetting[K <: FeatureKind](val value: String)
sealed abstract class PermissionFeatureSetting(override val value: String, val roles: Set[Option[OrganizationRole]]) extends FeatureSetting[FeatureKind.Permission](value)
object FeatureSetting {
  case object DISABLED extends PermissionFeatureSetting("disabled", OrganizationRole.NOONE)
  case object MEMBERS extends PermissionFeatureSetting("members", OrganizationRole.MEMBERS_UP)
  case object ADMINS extends PermissionFeatureSetting("admins", OrganizationRole.ADMINS_UP)
  case object ANYONE extends PermissionFeatureSetting("anyone", OrganizationRole.ANYONE_UP)
}


case class FeatureSettingPair[K <: FeatureKind](feature: Feature[K], setting: FeatureSetting[K])


object Feature {
  def apply(str: String): Feature[_ <: FeatureKind] = str match {
    case PublishLibraries.value => PublishLibraries
    case InviteMembers.value => InviteMembers
    case MessageOrganization.value => MessageOrganization
    case ForceEditLibraries.value => ForceEditLibraries
    case ViewOrganization.value => ViewOrganization
    case ViewMembers.value => ViewMembers
    case RemoveLibraries.value => RemoveLibraries
    case CreateSlackIntegration.value => CreateSlackIntegration
    case EditOrganization.value => EditOrganization
    case ExportKeeps.value => ExportKeeps
  }

  implicit val format: Format[Feature[_]] = Format(
    __.read[String].map(Feature(_)),
    Writes { x => JsString(x.value) }
  )

  import FeatureSetting._
  case object PublishLibraries extends PermissionFeature(OrganizationPermission.PUBLISH_LIBRARIES) {
    val settings: Set[FeatureSetting[FeatureKind.Permission]] = Set(DISABLED, ADMINS, MEMBERS)
  }

  case object InviteMembers extends PermissionFeature(OrganizationPermission.INVITE_MEMBERS) {
    val settings: Set[FeatureSetting[FeatureKind.Permission]] = Set(DISABLED, ADMINS, MEMBERS)
  }

  case object MessageOrganization extends PermissionFeature(OrganizationPermission.MESSAGE_ORGANIZATION) {
    val settings: Set[FeatureSetting[FeatureKind.Permission]] = Set(DISABLED, ADMINS, MEMBERS)
  }

  case object ForceEditLibraries extends PermissionFeature(OrganizationPermission.FORCE_EDIT_LIBRARIES) {
    val settings: Set[FeatureSetting[FeatureKind.Permission]] = Set(DISABLED, ADMINS, MEMBERS)
  }

  case object ViewOrganization extends PermissionFeature(OrganizationPermission.VIEW_ORGANIZATION) {
    val settings: Set[FeatureSetting[FeatureKind.Permission]] = Set(MEMBERS, ANYONE)
  }

  case object ViewMembers extends PermissionFeature(OrganizationPermission.VIEW_MEMBERS) {
    val settings: Set[FeatureSetting[FeatureKind.Permission]] = Set(DISABLED, ADMINS, MEMBERS, ANYONE)
  }

  case object RemoveLibraries extends PermissionFeature(OrganizationPermission.REMOVE_LIBRARIES) {
    val settings: Set[FeatureSetting[FeatureKind.Permission]] = Set(DISABLED, ADMINS, MEMBERS)
  }

  case object CreateSlackIntegration extends PermissionFeature(OrganizationPermission.CREATE_SLACK_INTEGRATION) {
    val settings: Set[FeatureSetting[FeatureKind.Permission]] = Set(DISABLED, ADMINS, MEMBERS)
  }

  case object EditOrganization extends PermissionFeature(OrganizationPermission.EDIT_ORGANIZATION) {
    val settings: Set[FeatureSetting[FeatureKind.Permission]] = Set(DISABLED, ADMINS, MEMBERS)
  }

  case object ExportKeeps extends PermissionFeature(OrganizationPermission.EXPORT_KEEPS) {
    val settings: Set[FeatureSetting[FeatureKind.Permission]] = Set(DISABLED, ADMINS, MEMBERS)
  }
}
