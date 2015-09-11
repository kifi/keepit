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

import scala.util.Try

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

object PermissionsFeatureNames {
  val ADD_PUBLIC_LIBRARY = Name[PlanFeature]("public_library_creation")
  val INVITE_MEMBERS = Name[PlanFeature]("invite_members")
  val GROUP_MESSAGING = Name[PlanFeature]("group_messaging")
  val MOVE_LIBRARY = Name[PlanFeature]("move_library")
  val EXPORT_KEEPS = Name[PlanFeature]("export_keeps")
  val VIEW_MEMBERS = Name[PlanFeature]("view_members")
  val EDIT_LIBRARY = Name[PlanFeature]("edit_library")

  val ALL = Set(ADD_PUBLIC_LIBRARY, INVITE_MEMBERS, GROUP_MESSAGING, MOVE_LIBRARY, EXPORT_KEEPS, VIEW_MEMBERS, EDIT_LIBRARY)
}

object PlanFeatureNames {
  val ALL = PermissionsFeatureNames // add any other group names here
}

trait PlanFeature {
  val name: Name[PlanFeature]
  val editable: Boolean
  val options: Seq[Setting]
  val default: Setting

  def toPermission(feature: PlanFeature): OrganizationPermission = {
    feature.name match {
      case PermissionsFeatureNames.ADD_PUBLIC_LIBRARY => OrganizationPermission.ADD_PUBLIC_LIBRARIES
      case PermissionsFeatureNames.INVITE_MEMBERS => OrganizationPermission.INVITE_MEMBERS
      case PermissionsFeatureNames.GROUP_MESSAGING => OrganizationPermission.GROUP_MESSAGING
      case PermissionsFeatureNames.MOVE_LIBRARY => OrganizationPermission.MOVE_LIBRARY
      case PermissionsFeatureNames.EXPORT_KEEPS => OrganizationPermission.EXPORT_KEEPS
      case PermissionsFeatureNames.VIEW_MEMBERS => OrganizationPermission.VIEW_MEMBERS
      case PermissionsFeatureNames.EDIT_LIBRARY => OrganizationPermission.FORCE_EDIT_LIBRARIES
      case _ => throw new Exception(s"[PlanFeature] called PlanFeature.toPermission with no mapping from $feature to an organization permission")
    }
  }
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
  implicit val format = new Format[PlanFeature] {
    def writes(o: PlanFeature): JsValue = {
      Json.obj("name" -> o.name.toString, "editable" -> o.editable, "options" -> o.options, "default" -> o.default)
    }
    def reads(json: JsValue): JsResult[PlanFeature] = {
      (json \ "name").as[Name[PlanFeature]](Name.format[PlanFeature]) match {
        case name if PermissionsFeatureNames.ALL.contains(name) => Json.fromJson[PermissionsFeature](json)
        case _ => throw new Exception(s"tried to read invalid json=$json into a PlanFeature")
      }
    }
  }
}
