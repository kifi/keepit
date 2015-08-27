package com.keepit.payments

import com.keepit.common.db.{ States, ModelWithState, Id, State }
import com.keepit.common.crypto.{ ModelWithPublicId, ModelWithPublicIdCompanion, PublicId, PublicIdConfiguration }
import com.keepit.common.time._
import com.keepit.model.{ Name, BasePermissions }

import com.kifi.macros.json

import org.joda.time.DateTime

import javax.crypto.spec.IvParameterSpec

case class BillingCycle(month: Int) extends AnyVal

@json
case class PlanFeature(name: String, displayName: String, editable: Boolean, default: Boolean)

@json
case class PlanFeatureSetting(feature: PlanFeature, enabled: Boolean)

object PlanFeatureSetting {
  def toBasePermissions(settings: Seq[PlanFeatureSetting]): BasePermissions = ??? //ZZZ TODO: When we know what the right permissions actually are.
}

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
    pricePerCyclePerUser: DollarAmount) extends ModelWithPublicId[PaidPlan] with ModelWithState[PaidPlan] {

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
