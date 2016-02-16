package com.keepit.model

import com.keepit.common.json.{ EnumFormat, TraversableFormat }
import com.keepit.common.reflection.Enumerator
import com.keepit.model.Feature.{ FeatureNotFoundException, InvalidSettingForFeatureException }
import play.api.libs.json._

sealed trait Feature {
  def value: String
  def editableWith: OrganizationPermission
  def settingReads: Reads[FeatureSetting]
}
object Feature {
  val all: Set[Feature] = StaticFeature.ALL.toSet ++ TextFeature.ALL.toSet

  def get(str: String): Option[Feature] = all.find(_.value == str)
  def apply(str: String): Feature = get(str).getOrElse(throw new FeatureNotFoundException(str))

  val format: Format[Feature] = EnumFormat.format[Feature](get, _.value)

  implicit val writes = Writes(format.writes)
  val reads: Reads[Feature] = Reads(format.reads)
  implicit val safeSetReads = TraversableFormat.safeSetReads[Feature](reads)

  case class InvalidSettingForFeatureException(feature: Feature, str: String) extends Exception(s""""$str" is not a valid setting for feature ${feature.value}""")
  case class FeatureNotFoundException(featureStr: String) extends Exception(s"""Feature "$featureStr" not found""")

}
sealed trait FeatureSetting {
  def value: String
}

case class OrganizationSettings(selections: Map[Feature, FeatureSetting]) {
  def features: Set[Feature] = selections.keySet
  def settingFor(f: Feature): Option[FeatureSetting] = selections.get(f)

  def withFeatureSetTo(fs: (Feature, FeatureSetting)): OrganizationSettings = setAll(Map(fs._1 -> fs._2))
  def overwriteWith(that: OrganizationSettings): OrganizationSettings = setAll(that.selections)
  def setAll(newSelections: Map[Feature, FeatureSetting]): OrganizationSettings = {
    copy(selections = selections ++ newSelections)
  }

  def extraPermissionsFor(roleOpt: Option[OrganizationRole]): Set[OrganizationPermission] = selections.collect {
    case (feature: FeatureWithPermissions, setting) => feature.extraPermissionsFor(roleOpt, setting)
  }.toSet.flatten

  def editedFeatures(that: OrganizationSettings): Set[Feature] = {
    selections.collect {
      case (feature, setting) if that.selections.contains(feature) && that.selections(feature) != setting => feature
    }.toSet
  }
}

object OrganizationSettings {
  val empty = OrganizationSettings(Map.empty)

  private val featureMapReads: Reads[Map[Feature, FeatureSetting]] = TraversableFormat.safeConditionalObjectReads[Feature, FeatureSetting](Feature.reads, _.settingReads)
  private val featuresFormat: Format[OrganizationSettings] = Format(
    Reads { jsv => jsv.validate[Map[Feature, FeatureSetting]](featureMapReads).map(m => OrganizationSettings(m.map(c => c._1 -> c._2))) },
    Writes { os => Json.toJson(os.selections.map { case (f, s) => f.value -> s.value }) }
  )

  val dbFormat = featuresFormat
  val siteFormat = featuresFormat
}

case class OrganizationSettingsWithEditability(settings: OrganizationSettings, editableFeatures: Set[Feature])
object OrganizationSettingsWithEditability {
  implicit val writes: Writes[OrganizationSettingsWithEditability] = Writes { orgSWE =>
    JsObject(orgSWE.settings.selections.map {
      case (feature, setting) => feature.value -> Json.obj("setting" -> setting.value, "editable" -> orgSWE.editableFeatures.contains(feature))
    }.toSeq)
  }
}

sealed trait StaticFeature extends Feature {
  val value: String
  val editableWith: OrganizationPermission
  protected def settings: Set[StaticFeatureSetting]

  def settingReads: Reads[FeatureSetting] = Reads { j => j.validate[String].map(toSetting) }
  private def toSetting(x: String): StaticFeatureSetting = settings.find(_.value == x).getOrElse(throw new InvalidSettingForFeatureException(this, x))
}
sealed trait FeatureWithPermissions {
  val permission: OrganizationPermission

  def affectedRoles(setting: FeatureSetting): Set[Option[OrganizationRole]] = setting match {
    case StaticFeatureSetting.NONMEMBERS => OrganizationRole.nonMemberSet
    case StaticFeatureSetting.MEMBERS => OrganizationRole.memberSet
    case StaticFeatureSetting.ADMINS => OrganizationRole.adminSet
    case StaticFeatureSetting.ANYONE => OrganizationRole.totalSet
    case _ => Set.empty
  }
  def extraPermissionsFor(roleOpt: Option[OrganizationRole], setting: FeatureSetting): Set[OrganizationPermission] = {
    if (affectedRoles(setting).contains(roleOpt)) Set(permission) else Set.empty
  }
}

sealed abstract class StaticFeatureSetting(val value: String) extends FeatureSetting
object StaticFeatureSetting {
  case object DISABLED extends StaticFeatureSetting("disabled")
  case object NONMEMBERS extends StaticFeatureSetting("nonmembers")
  case object MEMBERS extends StaticFeatureSetting("members")
  case object ADMINS extends StaticFeatureSetting("admins")
  case object ANYONE extends StaticFeatureSetting("anyone")
  case object ENABLED extends StaticFeatureSetting("enabled")
}

object StaticFeature extends Enumerator[StaticFeature] {

  val ALL: Seq[StaticFeature] = _all

  import StaticFeatureSetting._
  case object PublishLibraries extends StaticFeature with FeatureWithPermissions {
    val value = OrganizationPermission.PUBLISH_LIBRARIES.value
    val permission = OrganizationPermission.PUBLISH_LIBRARIES
    val settings: Set[StaticFeatureSetting] = Set(DISABLED, ADMINS, MEMBERS)
    val editableWith = OrganizationPermission.MANAGE_PLAN
  }

  case object InviteMembers extends StaticFeature with FeatureWithPermissions {
    val value = OrganizationPermission.INVITE_MEMBERS.value
    val permission = OrganizationPermission.INVITE_MEMBERS
    val settings: Set[StaticFeatureSetting] = Set(DISABLED, ADMINS, MEMBERS)
    val editableWith = OrganizationPermission.MANAGE_PLAN
  }

  case object GroupMessaging extends StaticFeature with FeatureWithPermissions {
    val value = OrganizationPermission.GROUP_MESSAGING.value
    val permission = OrganizationPermission.GROUP_MESSAGING
    val settings: Set[StaticFeatureSetting] = Set(DISABLED, ADMINS, MEMBERS)
    val editableWith = OrganizationPermission.MANAGE_PLAN
  }

  case object ForceEditLibraries extends StaticFeature with FeatureWithPermissions {
    val value = OrganizationPermission.FORCE_EDIT_LIBRARIES.value
    val permission = OrganizationPermission.FORCE_EDIT_LIBRARIES
    val settings: Set[StaticFeatureSetting] = Set(DISABLED, ADMINS)
    val editableWith = OrganizationPermission.MANAGE_PLAN
  }

  case object ViewOrganization extends StaticFeature with FeatureWithPermissions {
    val value = OrganizationPermission.VIEW_ORGANIZATION.value
    val permission = OrganizationPermission.VIEW_ORGANIZATION
    val settings: Set[StaticFeatureSetting] = Set(MEMBERS, ANYONE)
    val editableWith = OrganizationPermission.MANAGE_PLAN
  }

  case object ViewMembers extends StaticFeature with FeatureWithPermissions {
    val value = OrganizationPermission.VIEW_MEMBERS.value
    val permission = OrganizationPermission.VIEW_MEMBERS
    val settings: Set[StaticFeatureSetting] = Set(DISABLED, ADMINS, MEMBERS, ANYONE)
    val editableWith = OrganizationPermission.MANAGE_PLAN
  }

  case object RemoveLibraries extends StaticFeature with FeatureWithPermissions {
    val value = OrganizationPermission.REMOVE_LIBRARIES.value
    val permission = OrganizationPermission.REMOVE_LIBRARIES
    val settings: Set[StaticFeatureSetting] = Set(ADMINS, MEMBERS)
    val editableWith = OrganizationPermission.MANAGE_PLAN
  }

  case object CreateSlackIntegration extends StaticFeature with FeatureWithPermissions {
    val value = OrganizationPermission.CREATE_SLACK_INTEGRATION.value
    val permission = OrganizationPermission.CREATE_SLACK_INTEGRATION
    val settings: Set[StaticFeatureSetting] = Set(ADMINS, MEMBERS)
    val editableWith = OrganizationPermission.MANAGE_PLAN
  }

  case object EditOrganization extends StaticFeature with FeatureWithPermissions {
    val value = OrganizationPermission.EDIT_ORGANIZATION.value
    val permission = OrganizationPermission.EDIT_ORGANIZATION
    val settings: Set[StaticFeatureSetting] = Set(DISABLED, ADMINS, MEMBERS)
    val editableWith = OrganizationPermission.MANAGE_PLAN
  }

  case object ExportKeeps extends StaticFeature with FeatureWithPermissions {
    val value = OrganizationPermission.EXPORT_KEEPS.value
    val permission = OrganizationPermission.EXPORT_KEEPS
    val settings: Set[StaticFeatureSetting] = Set(DISABLED, ADMINS, MEMBERS)
    val editableWith = OrganizationPermission.MANAGE_PLAN
  }

  case object ViewSettings extends StaticFeature with FeatureWithPermissions {
    val value = OrganizationPermission.VIEW_SETTINGS.value
    val permission = OrganizationPermission.VIEW_SETTINGS
    val settings: Set[StaticFeatureSetting] = Set(ADMINS, MEMBERS)
    val editableWith = OrganizationPermission.MANAGE_PLAN
  }

  case object JoinByVerifying extends StaticFeature with FeatureWithPermissions {
    val value = OrganizationPermission.JOIN_BY_VERIFYING.value
    val permission = OrganizationPermission.JOIN_BY_VERIFYING
    val settings: Set[StaticFeatureSetting] = Set(DISABLED, NONMEMBERS)
    val editableWith = OrganizationPermission.MANAGE_PLAN
  }

  case object SlackIngestionReaction extends StaticFeature {
    val value = "slack_ingestion_reaction"
    val settings: Set[StaticFeatureSetting] = Set(DISABLED, ENABLED)
    val editableWith = OrganizationPermission.CREATE_SLACK_INTEGRATION
  }

  case object SlackNotifications extends StaticFeature {
    val value = "slack_digest_notif"
    val settings: Set[StaticFeatureSetting] = Set(DISABLED, ENABLED)
    val editableWith = OrganizationPermission.CREATE_SLACK_INTEGRATION
  }
}

case class TextFeatureSetting(val value: String) extends FeatureSetting
sealed trait TextFeature extends Feature {
  val value: String
  val editableWith: OrganizationPermission
  protected def parse(x: String): Option[TextFeatureSetting]

  def settingReads: Reads[FeatureSetting] = Reads { j =>
    j.validate[String].flatMap { r =>
      parse(r) match {
        case Some(valid) => JsSuccess(valid)
        case None => JsError("invalid_setting_value")
      }
    }
  }
}

object TextFeature extends Enumerator[TextFeature] {

  val ALL: Seq[TextFeature] = _all

  case object SlackIngestionDomainBlacklist extends TextFeature {
    val value = "slack_ingestion_domain_blacklist"
    val editableWith = OrganizationPermission.CREATE_SLACK_INTEGRATION
    def parse(x: String) = // See SlackIngestingBlacklist.parseBlacklist for how this is parsed into paths
      Some(TextFeatureSetting(x))
  }
}
