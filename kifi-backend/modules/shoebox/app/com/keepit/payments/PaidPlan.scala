package com.keepit.payments

import com.keepit.common.db.{ States, ModelWithState, Id, State }
import com.keepit.common.crypto.{ ModelWithPublicId, ModelWithPublicIdCompanion }
import com.keepit.common.time._
import com.keepit.model.Name

import org.joda.time.DateTime

import javax.crypto.spec.IvParameterSpec

case class BillingCycle(month: Int) extends AnyVal

case class PaidPlan(
    id: Option[Id[PaidPlan]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[PaidPlan] = PaidPlanStates.ACTIVE,
    name: Name[PaidPlan],
    billingCycle: BillingCycle,
    pricePerCyclePerUser: DollarAmount) extends ModelWithPublicId[PaidPlan] with ModelWithState[PaidPlan] {

  def withId(id: Id[PaidPlan]): PaidPlan = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime): PaidPlan = this.copy(updatedAt = now)
  def withState(state: State[PaidPlan]): PaidPlan = this.copy(state = state)
}

object PaidPlan extends ModelWithPublicIdCompanion[PaidPlan] {
  protected[this] val publicIdPrefix = "pp"
  protected[this] val publicIdIvSpec = new IvParameterSpec(Array(-81, 48, 82, -97, 110, 73, -46, -55, 43, 73, -107, -90, 89, 21, 116, -101))
}

object PaidPlanStates extends States[PaidPlan] {
  val GRANDFATHERED = State[PaidPlan]("grandfathered")
  val CUSTOM = State[PaidPlan]("custom")
}
