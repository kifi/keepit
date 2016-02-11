package com.keepit.model

import com.keepit.common.json.{ EnumFormat, TraversableFormat }
import com.keepit.common.reflection.Enumerator
import com.keepit.model.Feature.InvalidSettingForFeatureException
import play.api.libs.json._

case class OrganizationSettings(kvs: Map[Feature, FeatureSelection]) {
  def features: Set[Feature] = kvs.keySet
  def settingFor(f: Feature): Option[FeatureSelection] = kvs.get(f)

  def withFeatureSetTo(fs: (Feature, FeatureSelection)): OrganizationSettings = setAll(Map(fs._1 -> fs._2))
  def setAll(newKvs: Map[Feature, FeatureSelection]): OrganizationSettings = this.copy(kvs = kvs ++ newKvs)
  def overwriteWith(that: OrganizationSettings): OrganizationSettings = this.setAll(that.kvs)

  def extraPermissionsFor(roleOpt: Option[OrganizationRole]): Set[OrganizationPermission] = kvs.collect {
    case (feature: FeatureWithPermissions, setting: FeatureSelection) => feature.extraPermissionsFor(roleOpt, setting)
  }.toSet.flatten

  def editedFeatures(that: OrganizationSettings): Set[Feature] = {
    this.kvs.collect {
      case (feature, setting) if that.kvs.contains(feature) && that.kvs(feature) != setting => feature
    }.toSet
  }
}

object OrganizationSettings {
  val empty = OrganizationSettings(Map.empty)

  private def settingReadsByFeature(f: Feature): Reads[FeatureSelection] = f.settingReads
  private val featureMapReads: Reads[Map[Feature, FeatureSelection]] = TraversableFormat.safeConditionalObjectReads[Feature, FeatureSelection](Feature.reads, settingReadsByFeature)
  private val featuresFormat: Format[OrganizationSettings] = Format(
    Reads { jsv => jsv.validate[Map[Feature, FeatureSelection]](featureMapReads).map(OrganizationSettings(_)) },
    Writes { os => Json.toJson(os.kvs.map { case (f, s) => f.value -> s.value }) }
  )

  val dbFormat = featuresFormat
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
  def settings: Set[FeatureSelection]
  val editableWith: OrganizationPermission

  private def toSetting(x: String): FeatureSelection = settings.find(_.value == x).getOrElse(throw new InvalidSettingForFeatureException(this, x))
  def settingReads: Reads[FeatureSelection] = Reads { j => j.validate[String].map(toSetting) }
}
sealed trait FeatureWithPermissions {
  val permission: OrganizationPermission

  def affectedRoles(setting: FeatureSelection): Set[Option[OrganizationRole]] = setting match {
    case FeatureSelection.NONMEMBERS => OrganizationRole.nonMemberSet
    case FeatureSelection.MEMBERS => OrganizationRole.memberSet
    case FeatureSelection.ADMINS => OrganizationRole.adminSet
    case FeatureSelection.ANYONE => OrganizationRole.totalSet
    case _ => Set.empty
  }
  def extraPermissionsFor(roleOpt: Option[OrganizationRole], setting: FeatureSelection): Set[OrganizationPermission] = {
    if (affectedRoles(setting).contains(roleOpt)) Set(permission) else Set.empty
  }
}

sealed abstract class FeatureSelection(val value: String)
object FeatureSelection {
  case object DISABLED extends FeatureSelection("disabled")
  case object NONMEMBERS extends FeatureSelection("nonmembers")
  case object MEMBERS extends FeatureSelection("members")
  case object ADMINS extends FeatureSelection("admins")
  case object ANYONE extends FeatureSelection("anyone")
  case object ENABLED extends FeatureSelection("enabled")
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

  import FeatureSelection._
  case object PublishLibraries extends Feature with FeatureWithPermissions {
    val value = OrganizationPermission.PUBLISH_LIBRARIES.value
    val permission = OrganizationPermission.PUBLISH_LIBRARIES
    val settings: Set[FeatureSelection] = Set(DISABLED, ADMINS, MEMBERS)
    val editableWith = OrganizationPermission.MANAGE_PLAN
  }

  case object InviteMembers extends Feature with FeatureWithPermissions {
    val value = OrganizationPermission.INVITE_MEMBERS.value
    val permission = OrganizationPermission.INVITE_MEMBERS
    val settings: Set[FeatureSelection] = Set(DISABLED, ADMINS, MEMBERS)
    val editableWith = OrganizationPermission.MANAGE_PLAN
  }

  case object GroupMessaging extends Feature with FeatureWithPermissions {
    val value = OrganizationPermission.GROUP_MESSAGING.value
    val permission = OrganizationPermission.GROUP_MESSAGING
    val settings: Set[FeatureSelection] = Set(DISABLED, ADMINS, MEMBERS)
    val editableWith = OrganizationPermission.MANAGE_PLAN
  }

  case object ForceEditLibraries extends Feature with FeatureWithPermissions {
    val value = OrganizationPermission.FORCE_EDIT_LIBRARIES.value
    val permission = OrganizationPermission.FORCE_EDIT_LIBRARIES
    val settings: Set[FeatureSelection] = Set(DISABLED, ADMINS)
    val editableWith = OrganizationPermission.MANAGE_PLAN
  }

  case object ViewOrganization extends Feature with FeatureWithPermissions {
    val value = OrganizationPermission.VIEW_ORGANIZATION.value
    val permission = OrganizationPermission.VIEW_ORGANIZATION
    val settings: Set[FeatureSelection] = Set(MEMBERS, ANYONE)
    val editableWith = OrganizationPermission.MANAGE_PLAN
  }

  case object ViewMembers extends Feature with FeatureWithPermissions {
    val value = OrganizationPermission.VIEW_MEMBERS.value
    val permission = OrganizationPermission.VIEW_MEMBERS
    val settings: Set[FeatureSelection] = Set(DISABLED, ADMINS, MEMBERS, ANYONE)
    val editableWith = OrganizationPermission.MANAGE_PLAN
  }

  case object RemoveLibraries extends Feature with FeatureWithPermissions {
    val value = OrganizationPermission.REMOVE_LIBRARIES.value
    val permission = OrganizationPermission.REMOVE_LIBRARIES
    val settings: Set[FeatureSelection] = Set(ADMINS, MEMBERS)
    val editableWith = OrganizationPermission.MANAGE_PLAN
  }

  case object CreateSlackIntegration extends Feature with FeatureWithPermissions {
    val value = OrganizationPermission.CREATE_SLACK_INTEGRATION.value
    val permission = OrganizationPermission.CREATE_SLACK_INTEGRATION
    val settings: Set[FeatureSelection] = Set(ADMINS, MEMBERS)
    val editableWith = OrganizationPermission.MANAGE_PLAN
  }

  case object EditOrganization extends Feature with FeatureWithPermissions {
    val value = OrganizationPermission.EDIT_ORGANIZATION.value
    val permission = OrganizationPermission.EDIT_ORGANIZATION
    val settings: Set[FeatureSelection] = Set(DISABLED, ADMINS, MEMBERS)
    val editableWith = OrganizationPermission.MANAGE_PLAN
  }

  case object ExportKeeps extends Feature with FeatureWithPermissions {
    val value = OrganizationPermission.EXPORT_KEEPS.value
    val permission = OrganizationPermission.EXPORT_KEEPS
    val settings: Set[FeatureSelection] = Set(DISABLED, ADMINS, MEMBERS)
    val editableWith = OrganizationPermission.MANAGE_PLAN
  }

  case object ViewSettings extends Feature with FeatureWithPermissions {
    val value = OrganizationPermission.VIEW_SETTINGS.value
    val permission = OrganizationPermission.VIEW_SETTINGS
    val settings: Set[FeatureSelection] = Set(ADMINS, MEMBERS)
    val editableWith = OrganizationPermission.MANAGE_PLAN
  }

  case object JoinByVerifying extends Feature with FeatureWithPermissions {
    val value = OrganizationPermission.JOIN_BY_VERIFYING.value
    val permission = OrganizationPermission.JOIN_BY_VERIFYING
    val settings: Set[FeatureSelection] = Set(DISABLED, NONMEMBERS)
    val editableWith = OrganizationPermission.MANAGE_PLAN
  }

  case object SlackIngestionReaction extends Feature {
    val value = "slack_ingestion_reaction"
    val settings: Set[FeatureSelection] = Set(DISABLED, ENABLED)
    val editableWith = OrganizationPermission.CREATE_SLACK_INTEGRATION
  }

  case object SlackNotifications extends Feature {
    val value = "slack_digest_notif"
    val settings: Set[FeatureSelection] = Set(DISABLED, ENABLED)
    val editableWith = OrganizationPermission.CREATE_SLACK_INTEGRATION
  }
}
