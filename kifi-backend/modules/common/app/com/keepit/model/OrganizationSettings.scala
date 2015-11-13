package com.keepit.model

import com.keepit.common.json.{ EnumFormat, TraversableFormat }
import com.keepit.common.reflection.Enumerator
import com.keepit.model.Feature.InvalidSettingForFeatureException
import play.api.libs.json._

case class OrganizationSettings(kvs: Map[Feature, FeatureSetting]) {
  def features: Set[Feature] = kvs.keySet
  def settingFor(f: Feature): Option[FeatureSetting] = kvs.get(f)

  def withFeatureSetTo(fs: (Feature, FeatureSetting)): OrganizationSettings = setAll(Map(fs._1 -> fs._2))
  def setAll(newKvs: Map[Feature, FeatureSetting]): OrganizationSettings = this.copy(kvs = kvs ++ newKvs)
  def overwriteWith(that: OrganizationSettings): OrganizationSettings = this.setAll(that.kvs)

  def extraPermissionsFor(roleOpt: Option[OrganizationRole]): Set[OrganizationPermission] = kvs.collect {
    case (feature: FeatureWithPermissions, setting: FeatureSetting) => feature.extraPermissionsFor(roleOpt, setting)
  }.toSet.flatten

  def diff(that: OrganizationSettings): Set[Feature] = {
    this.kvs.collect {
      case (feature, setting) if that.kvs(feature) != setting => feature
    }.toSet
  }
}

object OrganizationSettings {
  val empty = OrganizationSettings(Map.empty)

  private def settingReadsByFeature(f: Feature): Reads[FeatureSetting] = f.settingReads
  private val featureMapReads: Reads[Map[Feature, FeatureSetting]] = TraversableFormat.safeConditionalObjectReads[Feature, FeatureSetting](Feature.reads, settingReadsByFeature)
  val dbFormat: Format[OrganizationSettings] = Format(
    Reads { jsv => jsv.validate[Map[Feature, FeatureSetting]](featureMapReads).map(OrganizationSettings(_)) },
    Writes { os => Json.toJson(os.kvs.map { case (f, s) => f.value -> s.value }) }
  )

  val siteFormat = dbFormat
}

case class OrganizationSettingsWithEditability(settings: OrganizationSettings, editableFeatures: Set[Feature])
object OrganizationSettingsWithEditability {
  implicit val writes: Writes[OrganizationSettingsWithEditability] = Writes { orgSWE =>
    JsObject(orgSWE.settings.kvs.map {
      case (feature, setting) => feature.value -> Json.obj("setting" -> setting.value, "editable" -> orgSWE.editableFeatures.contains(feature))
    }.toSeq)
  }
}

sealed trait Feature {
  val value: String
  def settings: Set[FeatureSetting]

  private def toSetting(x: String): FeatureSetting = settings.find(_.value == x).getOrElse(throw new InvalidSettingForFeatureException(this, x))
  def settingReads: Reads[FeatureSetting] = Reads { j => j.validate[String].map(toSetting) }
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

object Feature extends Enumerator[Feature] {
  case class InvalidSettingForFeatureException(feature: Feature, str: String) extends Exception(s""""$str" is not a valid setting for feature ${feature.value}""")
  case class FeatureNotFoundException(featureStr: String) extends Exception(s"""Feature "$featureStr" not found""")

  val ALL: Set[Feature] = _all.toSet

  def get(str: String): Option[Feature] = ALL.find(_.value == str)
  def apply(str: String): Feature = get(str).getOrElse(throw new FeatureNotFoundException(str))

  val format: Format[Feature] = Format(
    EnumFormat.reads(get, ALL.map(_.value)),
    Writes { x => JsString(x.value) }
  )

  implicit val writes = Writes(format.writes)
  val reads = Reads(format.reads)
  implicit val safeSetReads = TraversableFormat.safeSetReads[Feature](reads)

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
    val settings: Set[FeatureSetting] = Set(DISABLED, ADMINS)
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
    val settings: Set[FeatureSetting] = Set(ADMINS, MEMBERS)
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

  case object ViewSettings extends Feature with FeatureWithPermissions {
    val value = OrganizationPermission.VIEW_SETTINGS.value
    val permission = OrganizationPermission.VIEW_SETTINGS
    val settings: Set[FeatureSetting] = Set(ADMINS, MEMBERS)
  }

  case object VerifyToJoin extends Feature with FeatureWithPermissions {
    val value = OrganizationPermission.VERIFY_TO_JOIN.value
    val permission = OrganizationPermission.VERIFY_TO_JOIN
    val settings: Set[FeatureSetting] = Set(DISABLED, ANYONE)
  }
}
