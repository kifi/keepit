package com.keepit.payments

import com.keepit.common.db.{ States, ModelWithState, Id, State }
import com.keepit.common.crypto.{ ModelWithPublicId, ModelWithPublicIdCompanion, PublicId, PublicIdConfiguration }
import com.keepit.common.time._
import com.keepit.model._
import play.api.data.validation.ValidationError

import play.api.libs.json._
import play.api.libs.functional.syntax._

import com.kifi.macros.json

import org.joda.time.DateTime

import javax.crypto.spec.IvParameterSpec

import play.api.libs.json.Format

import scala.util.{ Success, Failure, Try }

case class BillingCycle(month: Int) extends AnyVal

@json
case class PaidPlanInfo(
  id: PublicId[PaidPlan],
  name: String)

case class PaidPlan(
    id: Option[Id[PaidPlan]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[PaidPlan] = PaidPlanStates.ACTIVE,
    kind: PaidPlan.Kind,
    name: Name[PaidPlan],
    billingCycle: BillingCycle,
    pricePerCyclePerUser: DollarAmount,
    features: Set[PlanFeature]) extends ModelWithPublicId[PaidPlan] with ModelWithState[PaidPlan] {

  def withId(id: Id[PaidPlan]): PaidPlan = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime): PaidPlan = this.copy(updatedAt = now)
  def withState(state: State[PaidPlan]): PaidPlan = this.copy(state = state)

  def asInfo(implicit config: PublicIdConfiguration): PaidPlanInfo = PaidPlanInfo(
    id = PaidPlan.publicId(id.get),
    name = name.name
  )
}

object PaidPlan extends ModelWithPublicIdCompanion[PaidPlan] {
  protected[this] val publicIdPrefix = "pp"
  protected[this] val publicIdIvSpec = new IvParameterSpec(Array(-81, 48, 82, -97, 110, 73, -46, -55, 43, 73, -107, -90, 89, 21, 116, -101))

  val DEFAULT = Id[PaidPlan](1L)

  case class Kind(name: String)
  object Kind {
    val NORMAL = Kind("normal")
    val GRANDFATHERED = Kind("grandfathered")
    val CUSTOM = Kind("custom")
  }
}

object PaidPlanStates extends States[PaidPlan]

sealed abstract class Setting(val value: String) {
  def writes = new Writes[Setting] {
    def writes(o: Setting) = JsString(o.value)
  }
}

case class PermissionSetting(role: Option[OrganizationRole]) extends Setting(role.map(_.value).getOrElse("none"))
object PermissionSetting {
  implicit val format = new Format[PermissionSetting] {
    def reads(json: JsValue): JsResult[PermissionSetting] = {
      json.validate[String].map { str =>
        if (str == "none") PermissionSetting(None) else PermissionSetting(Some(OrganizationRole(str)))
      }
    }

    def writes(o: PermissionSetting): JsValue = JsString(o.role.map(_.value).getOrElse("none"))
  }

  def apply(value: String): PermissionSetting = {
    value match {
      case value if Set("member", "admin").contains(value) => PermissionSetting(Some(OrganizationRole(value)))
      case "none" => PermissionSetting(None)
      case _ => throw new Exception(s"tried calling PermissionSetting.apply with invalid value=$value")
    }
  }
}

trait FeatureSetting {
  val name: Name[PlanFeature]
  val setting: Setting

  def isPermissionFeature = PermissionsFeatureNames.ALL.contains(this.name)
}
object FeatureSetting {

  implicit val format = new Format[FeatureSetting] {
    def reads(json: JsValue): JsResult[FeatureSetting] = {
      ((json \ "name").as[String], (json \ "setting").as[String]) match {
        case (name, setting) if PermissionsFeatureNames.ALL contains Name[PlanFeature](name) => JsSuccess(PermissionFeatureSetting(name, setting)) // I don't need Name[PlanFeature] because of type erasure, right?
        case _ => JsError(s"tried to call FeatureSettings.reads on json=$json with an unsupported name")
      }
    }
    def writes(o: FeatureSetting): JsValue = Json.obj("name" -> o.name.name, "setting" -> o.setting.value)
  }

  def apply(name: String, setting: String): FeatureSetting = {
    name match {
      case name if PermissionsFeatureNames.contains(name) => PermissionFeatureSetting(name, setting)
      case _ => throw new IllegalArgumentException(s"tried to call FeatureSetting.apply with unsupported name=$name")
    }
  }
  def apply(name: Name[PlanFeature], setting: Setting): FeatureSetting = FeatureSetting(name.name, setting.value)

  def isPermissionFeature(featureSetting: FeatureSetting): Boolean = PermissionsFeatureNames.ALL.contains(featureSetting.name)

  def toSettingByName(featureSettings: Set[FeatureSetting]): Map[Name[PlanFeature], Setting] = featureSettings.map { featureSetting => featureSetting.name -> featureSetting.setting }.toMap
  def fromSettingByName(settingByName: Map[Name[PlanFeature], Setting]): Set[FeatureSetting] = settingByName.map { case (name, setting) => FeatureSetting(name, setting) }.toSet

  def toBasePermissions(featureSettings: Set[FeatureSetting]): BasePermissions = {
    val namesAndSettings = FeatureSetting.toSettingByName(featureSettings).toSet

    // collects values of type PermissionSetting in settings, mapping them to a BasePermissions
    val permissionsByRole = namesAndSettings.collect { case (name, setting: PermissionSetting) => setting.role -> PlanFeature.toPermission(name) }
      .groupBy(_._1).map { case (role, rolesAndPermissions) => role -> rolesAndPermissions.map { _._2 } }
    BasePermissions(permissionsByRole)
  }

  def toPermissionsByRole(featureSettings: Set[FeatureSetting]): Map[Option[OrganizationRole], Set[OrganizationPermission]] = {
    val permissionFeatureSettings = featureSettings.collect { case featureSetting if featureSetting.isPermissionFeature => PermissionFeatureSetting(featureSetting.name.name, featureSetting.setting.value) }

    permissionFeatureSettings.flatMap {
      case PermissionFeatureSetting(name, PermissionSetting(None)) if PermissionsFeatureNames.CASCADING.contains(name) => {
        List(None -> PlanFeature.toPermission(name), Some(OrganizationRole.MEMBER) -> PlanFeature.toPermission(name), Some(OrganizationRole.ADMIN) -> PlanFeature.toPermission(name))
      }
      case PermissionFeatureSetting(name, PermissionSetting(Some(OrganizationRole.MEMBER))) if PermissionsFeatureNames.CASCADING.contains(name) => {
        List(Some(OrganizationRole.MEMBER) -> PlanFeature.toPermission(name), Some(OrganizationRole.ADMIN) -> PlanFeature.toPermission(name))
      }
      case PermissionFeatureSetting(name, setting) => List(setting.role -> PlanFeature.toPermission(name))
    }.groupBy(_._1).map { case (role, rolesAndPermissions) => role -> rolesAndPermissions.map(_._2) }
  }
}

case class PermissionFeatureSetting(name: Name[PlanFeature], setting: PermissionSetting) extends FeatureSetting
object PermissionFeatureSetting {
  def apply(name: String, setting: String): PermissionFeatureSetting = {
    if (PermissionsFeatureNames.ALL.contains(Name[PlanFeature](name))) {
      setting match {
        case none if none.toLowerCase == "none" => PermissionFeatureSetting(Name[PlanFeature](name), PermissionSetting(role = None))
        case role => PermissionFeatureSetting(Name[PlanFeature](name), PermissionSetting(Some(OrganizationRole(role))))
      }
    } else { throw new IllegalArgumentException(s"tried calling PermissionFeatureSetting.apply with unsupported name=$name and setting=$setting") }
  }

  def format = new Format[PermissionFeatureSetting] {
    def reads(json: JsValue): JsResult[PermissionFeatureSetting] = {
      ((json \ "name").as[String], (json \ "string").as[String]) match {
        case (name, setting) if PermissionsFeatureNames.ALL contains Name[PlanFeature](name) => JsSuccess(PermissionFeatureSetting(name, setting))
        case _ => JsError(s"tried to call PermissionFeatureSetting.apply on unsupported json=$json")
      }
    }
    def writes(o: PermissionFeatureSetting): JsValue = Json.obj("name" -> o.name.name, "setting" -> o.setting.value)
  }
}

object PermissionsFeatureNames {
  val PUBLISH_LIBRARIES = Name[PlanFeature](OrganizationPermission.PUBLISH_LIBRARIES.value)
  val INVITE_MEMBERS = Name[PlanFeature](OrganizationPermission.INVITE_MEMBERS.value)
  val GROUP_MESSAGING = Name[PlanFeature](OrganizationPermission.GROUP_MESSAGING.value)
  val RECLAIM_LIBRARIES = Name[PlanFeature](OrganizationPermission.MOVE_ORG_LIBRARIES.value)
  val EXPORT_KEEPS = Name[PlanFeature](OrganizationPermission.EXPORT_KEEPS.value)
  val VIEW_MEMBERS = Name[PlanFeature](OrganizationPermission.VIEW_MEMBERS.value)
  val FORCE_EDIT_LIBRARIES = Name[PlanFeature](OrganizationPermission.FORCE_EDIT_LIBRARIES.value)

  /// PermissionsFeatureNames.CASCADING are permissions that if applied to a role, must be applied to all roles "above" it None -> OrganizationRole.MEMBER -> OrganizationRole.ADMIN
  val CASCADING = Set(PUBLISH_LIBRARIES, INVITE_MEMBERS, GROUP_MESSAGING, RECLAIM_LIBRARIES, EXPORT_KEEPS, VIEW_MEMBERS, FORCE_EDIT_LIBRARIES)

  val ALL = Set(PUBLISH_LIBRARIES, INVITE_MEMBERS, GROUP_MESSAGING, RECLAIM_LIBRARIES, EXPORT_KEEPS, VIEW_MEMBERS, FORCE_EDIT_LIBRARIES)

  def contains(name: String) = ALL.contains(Name[PlanFeature](name))
}

object PlanFeatureNames {
  val ALL = PermissionsFeatureNames.ALL // all plan feature names
}

trait PlanFeature {
  val name: Name[PlanFeature]
  val editable: Boolean
  val options: Seq[Setting]
  val default: Setting
}

case class PermissionsFeature(name: Name[PlanFeature], editable: Boolean, options: Seq[PermissionSetting], default: PermissionSetting) extends PlanFeature
object PermissionsFeature {
  implicit val format: Format[PermissionsFeature] = (
    (__ \ 'name).format(Name.format[PlanFeature]) and
    (__ \ 'editable).format[Boolean] and
    (__ \ 'options).format[Seq[PermissionSetting]] and
    (__ \ 'default).format[PermissionSetting]
  )(PermissionsFeature.apply, unlift(PermissionsFeature.unapply))
}

object PlanFeature {
  def toPermission(name: Name[PlanFeature]): OrganizationPermission = {
    name match {
      case PermissionsFeatureNames.PUBLISH_LIBRARIES => OrganizationPermission.PUBLISH_LIBRARIES
      case PermissionsFeatureNames.INVITE_MEMBERS => OrganizationPermission.INVITE_MEMBERS
      case PermissionsFeatureNames.GROUP_MESSAGING => OrganizationPermission.GROUP_MESSAGING
      case PermissionsFeatureNames.RECLAIM_LIBRARIES => OrganizationPermission.MOVE_ORG_LIBRARIES
      case PermissionsFeatureNames.EXPORT_KEEPS => OrganizationPermission.EXPORT_KEEPS
      case PermissionsFeatureNames.VIEW_MEMBERS => OrganizationPermission.VIEW_MEMBERS
      case PermissionsFeatureNames.FORCE_EDIT_LIBRARIES => OrganizationPermission.FORCE_EDIT_LIBRARIES
      case _ => throw new IllegalArgumentException(s"[PlanFeature] called PlanFeature.toPermission with no mapping from $name to an organization permission")
    }
  }

  implicit val format = new Format[PlanFeature] {
    def writes(o: PlanFeature): JsValue = {
      Json.obj("name" -> o.name.toString, "editable" -> o.editable, "options" -> o.options.map(_.value), "default" -> o.default.value)
    }
    def reads(json: JsValue): JsResult[PlanFeature] = {
      (json \ "name").as[Name[PlanFeature]](Name.format[PlanFeature]) match {
        case name if PermissionsFeatureNames.ALL.contains(name) => Json.fromJson[PermissionsFeature](json)
        case _ => throw new IllegalArgumentException(s"tried to read invalid json=$json into a PlanFeature")
      }
    }
  }

  def apply(name: Name[PlanFeature], editable: Boolean, options: Seq[Setting], default: Setting): PlanFeature = {
    name match {
      case name if PermissionsFeatureNames.ALL.contains(name) => PermissionsFeature(name, editable, options.map(setting => PermissionSetting(setting.value)), PermissionSetting(default.value))
      case _ => throw new Exception(s"tried to call PlanFeature.apply with unsupported name=$name")
    }
  }

  def toDefaultFeatureSettings(features: Set[PlanFeature]): Set[FeatureSetting] = {
    features.map {
      case feature: PlanFeature if PermissionsFeatureNames.ALL.contains(feature.name) => PermissionFeatureSetting(feature.name.name, feature.default.value)
      case feature: PlanFeature => throw new IllegalArgumentException(s"tried to call PlanFeature.toDefaultFeatureSettings with unsupported name=${feature.name}")
    }
  }

  def isPermissionFeature(feature: PlanFeature): Boolean = PermissionsFeatureNames.ALL.contains(feature.name)
}
