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
    editableFeatures: Set[Feature],
    defaultSettings: OrganizationSettings) extends ModelWithPublicId[PaidPlan] with ModelWithState[PaidPlan] {

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

  @json
  case class Kind(name: String) extends AnyVal
  object Kind {
    val NORMAL = Kind("normal")
    val GRANDFATHERED = Kind("grandfathered")
    val CUSTOM = Kind("custom")
  }
}

object PaidPlanStates extends States[PaidPlan]

sealed trait Feature {
  def name: String
  def options: Seq[String]
  def verify(setting: String) = options.contains(setting)
}
object Feature {
  import OrganizationPermissionFeature._
  def get(name: String): Option[Feature] = ALL.find(_.name == name)
  val ALL: Set[Feature] = Set(PublishLibraries, InviteMembers, GroupMessaging, EditLibrary, ViewMembers, RemoveOrganizationLibraries, CreateSlackIntegration, EditOrganization, ExportKeeps)
}
sealed abstract class OrganizationPermissionFeature(val permission: OrganizationPermission) extends Feature {
  def roleOptions: Map[String, Seq[Option[OrganizationRole]]]
  def name = permission.value
  def options = roleOptions.keys.toSeq
  def permissionsByRoleBySetting: Map[String, PermissionsMap] = {
    roleOptions.map {
      case (setting, roles) =>
        val permissionsByRole = roles.map { role => role -> this.permission }
          .groupBy { _._1 }.map {
            case (role, roleAndPermissions) => role -> roleAndPermissions.map(_._2).toSet
          }
        setting -> PermissionsMap(permissionsByRole)
    }
  }
}
object OrganizationPermissionFeature {

  case object PublishLibraries extends OrganizationPermissionFeature(OrganizationPermission.PUBLISH_LIBRARIES) {
    val roleOptions = Map("disabled" -> Seq.empty, "admin" -> Seq(Some(OrganizationRole.ADMIN)), "member" -> Seq(Some(OrganizationRole.ADMIN), Some(OrganizationRole.MEMBER)))
  }

  case object InviteMembers extends OrganizationPermissionFeature(OrganizationPermission.INVITE_MEMBERS) {
    val roleOptions = Map("disabled" -> Seq.empty, "admin" -> Seq(Some(OrganizationRole.ADMIN)), "member" -> Seq(Some(OrganizationRole.ADMIN), Some(OrganizationRole.MEMBER)))
  }

  case object GroupMessaging extends OrganizationPermissionFeature(OrganizationPermission.MESSAGE_ORGANIZATION) {
    val roleOptions = Map("disabled" -> Seq.empty, "admin" -> Seq(Some(OrganizationRole.ADMIN)), "member" -> Seq(Some(OrganizationRole.ADMIN), Some(OrganizationRole.MEMBER)))
  }

  case object EditLibrary extends OrganizationPermissionFeature(OrganizationPermission.FORCE_EDIT_LIBRARIES) {
    val roleOptions = Map("disabled" -> Seq.empty, "admin" -> Seq(Some(OrganizationRole.ADMIN)), "member" -> Seq(Some(OrganizationRole.ADMIN), Some(OrganizationRole.MEMBER)))
  }

  case object ViewMembers extends OrganizationPermissionFeature(OrganizationPermission.VIEW_MEMBERS) {
    val roleOptions = Map("anyone" -> Seq(Some(OrganizationRole.ADMIN), Some(OrganizationRole.MEMBER), None), "member" -> Seq(Some(OrganizationRole.ADMIN), Some(OrganizationRole.MEMBER)))
  }

  case object RemoveOrganizationLibraries extends OrganizationPermissionFeature(OrganizationPermission.REMOVE_LIBRARIES) {
    val roleOptions = Map("disabled" -> Seq.empty, "admin" -> Seq(Some(OrganizationRole.ADMIN)), "member" -> Seq(Some(OrganizationRole.MEMBER), Some(OrganizationRole.ADMIN)))
  }

  case object CreateSlackIntegration extends OrganizationPermissionFeature(OrganizationPermission.CREATE_SLACK_INTEGRATION) {
    val roleOptions = Map("disabled" -> Seq.empty, "member" -> Seq(Some(OrganizationRole.MEMBER), Some(OrganizationRole.ADMIN)), "admin" -> Seq(Some(OrganizationRole.ADMIN)))
  }

  case object EditOrganization extends OrganizationPermissionFeature(OrganizationPermission.EDIT_ORGANIZATION) {
    val roleOptions = Map("member" -> Seq(Some(OrganizationRole.MEMBER), Some(OrganizationRole.ADMIN)), "admin" -> Seq(Some(OrganizationRole.ADMIN)))
  }

  case object ExportKeeps extends OrganizationPermissionFeature(OrganizationPermission.EXPORT_KEEPS) {
    val roleOptions = Map("disabled" -> Seq.empty, "member" -> Seq(Some(OrganizationRole.MEMBER), Some(OrganizationRole.ADMIN)), "admin" -> Seq(Some(OrganizationRole.ADMIN)))
  }
}

@json
case class PlanFeature(name: String, default: String, editable: Boolean) // Stored in db for PaidPlan
@json
case class ClientFeature(name: String, setting: String, editable: Boolean) // Sent to clients
@json
case class FeatureSetting(name: String, setting: String) // Received from clients, stored in db for PaidAccount

object FeatureSetting {
  def alterSettings(featureSettings: Set[FeatureSetting], toChange: Set[FeatureSetting]): Set[FeatureSetting] = {
    featureSettings -- toChange.map(newSetting => featureSettings.find(_.name == newSetting.name).get) ++ toChange
  }
}
