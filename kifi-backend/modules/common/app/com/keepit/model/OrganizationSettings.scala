package com.keepit.model

import play.api.libs.json._

case class OrganizationSettings(kvs: Map[Feature, FeatureSetting]) {
  def setAll(newKvs: Map[Feature, FeatureSetting]): OrganizationSettings = {
    this.copy(kvs = kvs ++ newKvs)
  }
  def withSettings(newKvs: (Feature, FeatureSetting)*): OrganizationSettings = setAll(newKvs.toMap)
  def extraPermissionsFor(roleOpt: Option[OrganizationRole]): Set[OrganizationPermission] = kvs.collect {
    case (feature: FeatureWithPermissions, setting: FeatureSetting) => feature.extraPermissionsFor(roleOpt, setting)
  }.toSet.flatten

  def diff(that: OrganizationSettings): Set[Feature] = {
    this.kvs.collect {
      case (feature, setting) if that.kvs(feature) != setting => feature
    }.toSet
  }

  override def toString: String = Json.toJson(this).toString()
}

object OrganizationSettings {
  val empty = OrganizationSettings(Map.empty)
  implicit val format: Format[OrganizationSettings] = Format(
    Reads { jsv =>
      jsv.validate[JsObject].map { obj =>
        OrganizationSettings(obj.fieldSet.map {
          case (f, s) =>
            val feature = Feature(f)
            val setting = s.as(feature.settingReads)
            feature -> setting
        }.toMap)
      }
    },
    Writes { orgSettings =>
      Json.toJson(orgSettings.kvs.map { case (feature, setting) => feature.value -> setting.value })
    }
  )
}

sealed trait Feature {
  val value: String
  def settings: Set[FeatureSetting]

  private def toSetting(x: String): Option[FeatureSetting] = settings.find(_.value == x)
  def settingReads: Reads[FeatureSetting] = __.read[String].map(x => toSetting(x).get)
}
sealed trait FeatureWithPermissions {
  val permission: OrganizationPermission

  def affectedRoles(setting: FeatureSetting): Set[Option[OrganizationRole]] = setting match {
    case FeatureSetting.MEMBERS => OrganizationRole.memberSet
    case FeatureSetting.ADMINS => OrganizationRole.adminSet
    case FeatureSetting.ANYONE => OrganizationRole.totalSet
    case _ => Set.empty
  }
  def extraPermissionsFor(roleOpt: Option[OrganizationRole], setting: FeatureSetting): Set[OrganizationPermission] = {
    if (affectedRoles(setting).contains(roleOpt)) Set(permission) else Set.empty
  }
}

sealed abstract class FeatureSetting(val value: String)
object FeatureSetting {
  case object DISABLED extends FeatureSetting("disabled")
  case object MEMBERS extends FeatureSetting("members")
  case object ADMINS extends FeatureSetting("admins")
  case object ANYONE extends FeatureSetting("anyone")
}

object Feature {
  val all: Set[Feature] = Set(
    PublishLibraries,
    InviteMembers,
    GroupMessaging,
    ForceEditLibraries,
    ViewOrganization,
    ViewMembers,
    RemoveLibraries,
    CreateSlackIntegration,
    EditOrganization,
    ExportKeeps
  )
  def apply(str: String): Feature = all.find(_.value == str).get

  implicit val format: Format[Feature] = Format(
    __.read[String].map(Feature(_)),
    Writes { x => JsString(x.value) }
  )

  import FeatureSetting._
  case object PublishLibraries extends Feature with FeatureWithPermissions {
    val value = OrganizationPermission.PUBLISH_LIBRARIES.value
    val permission = OrganizationPermission.PUBLISH_LIBRARIES
    val settings: Set[FeatureSetting] = Set(DISABLED, ADMINS, MEMBERS)
  }

  case object InviteMembers extends Feature with FeatureWithPermissions {
    val value = OrganizationPermission.INVITE_MEMBERS.value
    val permission = OrganizationPermission.INVITE_MEMBERS
    val settings: Set[FeatureSetting] = Set(DISABLED, ADMINS, MEMBERS)
  }

  case object GroupMessaging extends Feature with FeatureWithPermissions {
    val value = OrganizationPermission.GROUP_MESSAGING.value
    val permission = OrganizationPermission.GROUP_MESSAGING
    val settings: Set[FeatureSetting] = Set(DISABLED, ADMINS, MEMBERS)
  }

  case object ForceEditLibraries extends Feature with FeatureWithPermissions {
    val value = OrganizationPermission.FORCE_EDIT_LIBRARIES.value
    val permission = OrganizationPermission.FORCE_EDIT_LIBRARIES
    val settings: Set[FeatureSetting] = Set(DISABLED, ADMINS, MEMBERS)
  }

  case object ViewOrganization extends Feature with FeatureWithPermissions {
    val value = OrganizationPermission.VIEW_ORGANIZATION.value
    val permission = OrganizationPermission.VIEW_ORGANIZATION
    val settings: Set[FeatureSetting] = Set(MEMBERS, ANYONE)
  }

  case object ViewMembers extends Feature with FeatureWithPermissions {
    val value = OrganizationPermission.VIEW_MEMBERS.value
    val permission = OrganizationPermission.VIEW_MEMBERS
    val settings: Set[FeatureSetting] = Set(DISABLED, ADMINS, MEMBERS, ANYONE)
  }

  case object RemoveLibraries extends Feature with FeatureWithPermissions {
    val value = OrganizationPermission.REMOVE_LIBRARIES.value
    val permission = OrganizationPermission.REMOVE_LIBRARIES
    val settings: Set[FeatureSetting] = Set(DISABLED, ADMINS, MEMBERS)
  }

  case object CreateSlackIntegration extends Feature with FeatureWithPermissions {
    val value = OrganizationPermission.CREATE_SLACK_INTEGRATION.value
    val permission = OrganizationPermission.CREATE_SLACK_INTEGRATION
    val settings: Set[FeatureSetting] = Set(DISABLED, ADMINS, MEMBERS)
  }

  case object EditOrganization extends Feature with FeatureWithPermissions {
    val value = OrganizationPermission.EDIT_ORGANIZATION.value
    val permission = OrganizationPermission.EDIT_ORGANIZATION
    val settings: Set[FeatureSetting] = Set(DISABLED, ADMINS, MEMBERS)
  }

  case object ExportKeeps extends Feature with FeatureWithPermissions {
    val value = OrganizationPermission.EXPORT_KEEPS.value
    val permission = OrganizationPermission.EXPORT_KEEPS
    val settings: Set[FeatureSetting] = Set(DISABLED, ADMINS, MEMBERS)
  }
}
