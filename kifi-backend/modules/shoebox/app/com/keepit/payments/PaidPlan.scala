package com.keepit.payments

import com.keepit.common.db.{ States, ModelWithState, Id, State }
import com.keepit.common.crypto.{ ModelWithPublicId, ModelWithPublicIdCompanion, PublicId, PublicIdConfiguration }
import com.keepit.common.time._
import com.keepit.model.{ OrganizationPermission, OrganizationRole, Name, BasePermissions }

import play.api.libs.json._
import play.api.libs.functional.syntax._

import com.kifi.macros.json

import org.joda.time.DateTime

import javax.crypto.spec.IvParameterSpec

import play.api.libs.json.Format

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

sealed abstract class PlanFeature(val name: String, val editable: Boolean, val default: Boolean)
object PlanFeature {
  case object ADD_PUBLIC_LIBRARY extends PlanFeature(name = "public_library_creation", editable = true, default = true)
  case object INVITE_MEMBERS extends PlanFeature(name = "invite_members", editable = true, default = true)
  case object GROUP_MESSAGING extends PlanFeature(name = "group_messaging", editable = true, default = true)
  case object MOVE_LIBRARY extends PlanFeature(name = "move_library", editable = true, default = true)
  case object EXPORT_KEEPS extends PlanFeature(name = "export_keeps", editable = false, default = true)
  case object VIEW_MEMBERS extends PlanFeature(name = "view_members", editable = false, default = true)
  case object EDIT_LIBRARY extends PlanFeature(name = "edit_library", editable = true, default = true)

  implicit val format: Format[PlanFeature] = new Format[PlanFeature] {
    def reads(json: JsValue): PlanFeature = PlanFeature((json \ "name").as[String])
    def writes(o: PlanFeature): JsValue = Json.obj("name" -> o.name)
  }

  def apply(name: String): PlanFeature = {
    name match {
      case ADD_PUBLIC_LIBRARY.name => ADD_PUBLIC_LIBRARY
      case INVITE_MEMBERS.name => INVITE_MEMBERS
      case GROUP_MESSAGING.name => GROUP_MESSAGING
      case MOVE_LIBRARY.name => MOVE_LIBRARY
      case EXPORT_KEEPS.name => EXPORT_KEEPS
      case VIEW_MEMBERS.name => VIEW_MEMBERS
      case EDIT_LIBRARY.name => EDIT_LIBRARY
    }
  }

  def toPermission(feature: PlanFeature) = {
    feature match {
      case ADD_PUBLIC_LIBRARY => OrganizationPermission.ADD_PUBLIC_LIBRARIES
      case INVITE_MEMBERS => OrganizationPermission.INVITE_MEMBERS
      case GROUP_MESSAGING => OrganizationPermission.GROUP_MESSAGING
      case MOVE_LIBRARY => OrganizationPermission.MOVE_LIBRARY
      case EXPORT_KEEPS => OrganizationPermission.EXPORT_KEEPS
      case VIEW_MEMBERS => OrganizationPermission.VIEW_MEMBERS
      case EDIT_LIBRARY => OrganizationPermission.FORCE_EDIT_LIBRARIES
      case _ => OrganizationPermission("none") // TODO: handle features which don't map to a permission
    }
  }
}

sealed abstract class PlanFeatureSetting(enabled: Boolean, value: String)
case class GenericFeatureSetting(enabled: Boolean, value: String) extends PlanFeatureSetting(enabled, value)
case class PermissionFeatureSetting(enabled: Boolean, role: Option[OrganizationRole]) extends PlanFeatureSetting(enabled, role.map(_.value).getOrElse("None"))

case class PlanSettingsConfiguration(settingsByFeature: Map[PlanFeature, PlanFeatureSetting])

object PlanSettingsConfiguration {
  val empty = PlanSettingsConfiguration(settingsByFeature = Map.empty)

  implicit val format = new Format[PlanSettingsConfiguration] {

    def reads(json: JsValue): PlanSettingsConfiguration = {
      val settingsByFeature = json.as[JsObject].fields.map {
        case (feature, setting) => PlanFeature(feature) -> GenericFeatureSetting((setting \ "enabled").as[Boolean], (setting \ "value").as[String])
      }.toMap
      PlanSettingsConfiguration(settingsByFeature)
    }

    def writes(o: PlanSettingsConfiguration): JsValue = {
      JsObject(o.settingsByFeature.map { case (feature, setting) => feature.name -> Json.toJson(setting) }.toSeq)
    }
  }

  def toBasePermissions(configuration: Map[PlanFeature, PlanFeatureSetting]): BasePermissions = {
    val permissionsByRole = configuration.toSeq.collect { // ignores GenericFeatureSettings
      case (feature, PermissionFeatureSetting(_, role)) => role -> PlanFeature.toPermission(feature)
    }.groupBy(_._1).map {
      case (role, roleAndPermissions) => (role, roleAndPermissions.map(_._2).toSet)
    }
    BasePermissions(permissionsByRole)
  }
}
