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

@json
case class BillingCycle(month: Int) extends AnyVal

@json
case class PaidPlanInfo(
  id: PublicId[PaidPlan],
  name: String,
  fullName: String,
  pricePerUser: DollarAmount,
  cycle: BillingCycle,
  features: Set[Feature])

case class PaidPlan(
    id: Option[Id[PaidPlan]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[PaidPlan] = PaidPlanStates.ACTIVE,
    kind: PaidPlan.Kind,
    name: Name[PaidPlan],
    displayName: String,
    billingCycle: BillingCycle,
    pricePerCyclePerUser: DollarAmount,
    editableFeatures: Set[Feature],
    defaultSettings: OrganizationSettings) extends ModelWithPublicId[PaidPlan] with ModelWithState[PaidPlan] {

  def withId(id: Id[PaidPlan]): PaidPlan = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime): PaidPlan = this.copy(updatedAt = now)
  def withState(state: State[PaidPlan]): PaidPlan = this.copy(state = state)

  def asInfo(implicit config: PublicIdConfiguration): PaidPlanInfo = PaidPlanInfo(
    id = PaidPlan.publicId(id.get),
    name = displayName,
    fullName = fullName,
    pricePerUser = pricePerCyclePerUser,
    cycle = billingCycle,
    features = editableFeatures
  )

  def fullName = {
    val cycleString = billingCycle.month match {
      case 0 => ""
      case 1 => " Monthly"
      case 12 => " Annual"
      case _ => " Custom"
    }
    displayName + cycleString
  }
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

