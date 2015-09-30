package com.keepit.model

import play.api.libs.json._

case class OrganizationSettings(kvs: Set[FeatureSettingPair[_ <: Feature]]) {
  def setAll(newKvs: Set[FeatureSettingPair[_ <: Feature]]): OrganizationSettings = {
    val newFeatures = newKvs.map(_.feature)
    this.copy(kvs = kvs.filter(kv => !newFeatures.contains(kv.feature)) ++ newKvs)
  }

  def set[F <: Feature](newKvs: (F, FeatureSetting[F])*): OrganizationSettings = setAll(newKvs.map(kv => FeatureSettingPair(kv._1, kv._2)).toSet)

  def extraPermissionsFor(roleOpt: Option[OrganizationRole]): Set[OrganizationPermission] = {
    kvs.flatMap { kv =>
      kv.permissionsFor(roleOpt)
    }
  }

  override def toString: String = Json.toJson(this).toString()
}

object OrganizationSettings {
  val empty = OrganizationSettings(Set.empty)
  implicit val format: Format[OrganizationSettings] = Format (
    Reads { jsv =>
      jsv.validate[JsObject].map { obj =>
        OrganizationSettings(obj.fieldSet.map { case (f, s) =>
          val feature = Feature(f)
          val setting = s.as(feature.settingFormat)
          FeatureSettingPair(feature.instance, setting)
        }.toSet)
      }
    },
    Writes { orgSettings =>
      Json.toJson(orgSettings.kvs.map(fsp => fsp.feature.value -> fsp.setting.value).toMap)
    }
  )
}

sealed trait Feature { self =>
  type F >: self.type <: Feature
  def instance: F = self
  def value: String
  def settings: Set[FeatureSetting[F]]
  def permissions: Set[OrganizationPermission]

  def toSetting(x: String): Option[FeatureSetting[F]] = settings.find(_.value == x)
  implicit def settingFormat: Format[FeatureSetting[F]] = Format(__.read[String].map(toSetting(_).get), Writes { x => JsString(x.value) } )
}

sealed abstract class FeatureSetting[F <: Feature](val value: String) {
  def rolesAffected: Set[Option[OrganizationRole]]
}

class FeatureSettingPair[F <: Feature](val feature: F, val setting: FeatureSetting[F]) {
  def permissionsFor(roleOpt: Option[OrganizationRole]): Set[OrganizationPermission] = {
    if (setting.rolesAffected.contains(roleOpt)) feature.permissions else Set.empty
  }
}
object FeatureSettingPair {
  def apply[F <: Feature](feature: F, setting: FeatureSetting[F]) = new FeatureSettingPair(feature, setting)
}

object Feature {
  def apply(str: String): Feature = str match {
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

  implicit val format: Format[Feature] = Format (
    __.read[String].map(Feature(_)),
    Writes { x => JsString(x.value) }
  )

  case object PublishLibraries extends Feature {
    type F = PublishLibraries.type
    val value = OrganizationPermission.PUBLISH_LIBRARIES.value
    def permissions = Set(OrganizationPermission.PUBLISH_LIBRARIES)

    case object DISABLED extends FeatureSetting[F]("disabled") { val rolesAffected = OrganizationRole.NOONE }
    case object ADMINS extends FeatureSetting[F]("admins") { val rolesAffected = OrganizationRole.ADMINS_UP }
    case object MEMBERS extends FeatureSetting[F]("members") { val rolesAffected = OrganizationRole.MEMBERS_UP }
    val settings: Set[FeatureSetting[F]] = Set(DISABLED, ADMINS, MEMBERS)
  }

  case object InviteMembers extends Feature {
    type F = InviteMembers.type
    val value = OrganizationPermission.INVITE_MEMBERS.value
    def permissions = Set(OrganizationPermission.INVITE_MEMBERS)

    case object DISABLED extends FeatureSetting[F]("disabled") { val rolesAffected = OrganizationRole.NOONE }
    case object ADMINS extends FeatureSetting[F]("admins") { val rolesAffected = OrganizationRole.ADMINS_UP }
    case object MEMBERS extends FeatureSetting[F]("members") { val rolesAffected = OrganizationRole.MEMBERS_UP }
    val settings: Set[FeatureSetting[F]] = Set(DISABLED, ADMINS, MEMBERS)
  }

  case object MessageOrganization extends Feature {
    type F = MessageOrganization.type
    val value = OrganizationPermission.MESSAGE_ORGANIZATION.value
    def permissions = Set(OrganizationPermission.MESSAGE_ORGANIZATION)

    case object DISABLED extends FeatureSetting[F]("disabled") { val rolesAffected = OrganizationRole.NOONE }
    case object ADMINS extends FeatureSetting[F]("admins") { val rolesAffected = OrganizationRole.ADMINS_UP }
    case object MEMBERS extends FeatureSetting[F]("members") { val rolesAffected = OrganizationRole.MEMBERS_UP }
    val settings: Set[FeatureSetting[F]] = Set(DISABLED, ADMINS, MEMBERS)
  }

  case object ForceEditLibraries extends Feature {
    type F = ForceEditLibraries.type
    val value = OrganizationPermission.FORCE_EDIT_LIBRARIES.value
    def permissions = Set(OrganizationPermission.FORCE_EDIT_LIBRARIES)

    case object DISABLED extends FeatureSetting[F]("disabled") { val rolesAffected = OrganizationRole.NOONE }
    case object ADMINS extends FeatureSetting[F]("admins") { val rolesAffected = OrganizationRole.ADMINS_UP }
    case object MEMBERS extends FeatureSetting[F]("members") { val rolesAffected = OrganizationRole.MEMBERS_UP }
    val settings: Set[FeatureSetting[F]] = Set(DISABLED, ADMINS, MEMBERS)
  }

  case object ViewOrganization extends Feature {
    type F = ViewOrganization.type
    val value = OrganizationPermission.VIEW_ORGANIZATION.value
    def permissions = Set(OrganizationPermission.VIEW_ORGANIZATION)

    case object MEMBERS extends FeatureSetting[F]("members") { val rolesAffected = OrganizationRole.MEMBERS_UP }
    case object ANYONE extends FeatureSetting[F]("anyone") { val rolesAffected = OrganizationRole.ANYONE_UP }
    val settings: Set[FeatureSetting[F]] = Set(MEMBERS, ANYONE)
  }

  case object ViewMembers extends Feature {
    type F = ViewMembers.type
    val value = OrganizationPermission.VIEW_MEMBERS.value
    def permissions = Set(OrganizationPermission.VIEW_MEMBERS)

    case object DISABLED extends FeatureSetting[F]("disabled") { val rolesAffected = OrganizationRole.NOONE }
    case object ADMINS extends FeatureSetting[F]("admins") { val rolesAffected = OrganizationRole.ADMINS_UP }
    case object MEMBERS extends FeatureSetting[F]("members") { val rolesAffected = OrganizationRole.MEMBERS_UP }
    case object ANYONE extends FeatureSetting[F]("anyone") { val rolesAffected = OrganizationRole.ANYONE_UP }
    val settings: Set[FeatureSetting[F]] = Set(DISABLED, ADMINS, MEMBERS, ANYONE)
  }

  case object RemoveLibraries extends Feature {
    type F = RemoveLibraries.type
    val value = OrganizationPermission.REMOVE_LIBRARIES.value
    def permissions = Set(OrganizationPermission.REMOVE_LIBRARIES)
    case object DISABLED extends FeatureSetting[F]("disabled") { val rolesAffected = OrganizationRole.NOONE }
    case object ADMINS extends FeatureSetting[F]("admins") { val rolesAffected = OrganizationRole.ADMINS_UP }
    case object MEMBERS extends FeatureSetting[F]("members") { val rolesAffected = OrganizationRole.MEMBERS_UP }
    val settings: Set[FeatureSetting[F]] = Set(DISABLED, ADMINS, MEMBERS)
  }

  case object CreateSlackIntegration extends Feature {
    type F = CreateSlackIntegration.type
    val value = OrganizationPermission.CREATE_SLACK_INTEGRATION.value
    def permissions = Set(OrganizationPermission.CREATE_SLACK_INTEGRATION)
    case object DISABLED extends FeatureSetting[F]("disabled") { val rolesAffected = OrganizationRole.NOONE }
    case object ADMINS extends FeatureSetting[F]("admins") { val rolesAffected = OrganizationRole.ADMINS_UP }
    case object MEMBERS extends FeatureSetting[F]("members") { val rolesAffected = OrganizationRole.MEMBERS_UP }
    val settings: Set[FeatureSetting[F]] = Set(DISABLED, ADMINS, MEMBERS)
  }

  case object EditOrganization extends Feature {
    type F = EditOrganization.type
    val value = OrganizationPermission.EDIT_ORGANIZATION.value
    def permissions = Set(OrganizationPermission.EDIT_ORGANIZATION)
    case object DISABLED extends FeatureSetting[F]("disabled") { val rolesAffected = OrganizationRole.NOONE }
    case object ADMINS extends FeatureSetting[F]("admins") { val rolesAffected = OrganizationRole.ADMINS_UP }
    case object MEMBERS extends FeatureSetting[F]("members") { val rolesAffected = OrganizationRole.MEMBERS_UP }
    val settings: Set[FeatureSetting[F]] = Set(DISABLED, ADMINS, MEMBERS)
  }

  case object ExportKeeps extends Feature {
    type F = ExportKeeps.type
    val value = OrganizationPermission.EXPORT_KEEPS.value
    def permissions = Set(OrganizationPermission.EXPORT_KEEPS)

    case object DISABLED extends FeatureSetting[F]("disabled") { val rolesAffected = OrganizationRole.NOONE }
    case object ADMINS extends FeatureSetting[F]("admins") { val rolesAffected = OrganizationRole.ADMINS_UP }
    case object MEMBERS extends FeatureSetting[F]("members") { val rolesAffected = OrganizationRole.MEMBERS_UP }
    val settings: Set[FeatureSetting[F]] = Set(DISABLED, ADMINS, MEMBERS)
  }
}
