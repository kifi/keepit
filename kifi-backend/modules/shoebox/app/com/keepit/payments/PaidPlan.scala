package com.keepit.payments

import com.keepit.common.db.{ States, ModelWithState, Id, State }
import com.keepit.common.crypto.{ ModelWithPublicId, PublicIdGenerator, PublicId, PublicIdConfiguration }
import com.keepit.common.time._
import com.keepit.common.util.DollarAmount
import com.keepit.model._

import play.api.libs.json._
import play.api.libs.functional.syntax._

import com.kifi.macros.json

import org.joda.time.DateTime

import javax.crypto.spec.IvParameterSpec

object PlanRenewalPolicy {
  def newPlansStartDate(now: DateTime): DateTime = {
    // changing this might mess up book keeping for teams that are waiting for a *new* plan to start, i.e. teams that have just been created / just changed their plan
    val thatTimeInHours = 20 // 8PM UTC = 1PM PST
    val thatTimeToday = now.withTimeAtStartOfDay() plusHours thatTimeInHours // Today 8PM UTC = 1PM PST
    val thatTimeTomorrow = (now plusDays 1).withTimeAtStartOfDay() plusHours thatTimeInHours // Tomorrow 8PM UTC = 1PM PST
    if (now isBefore thatTimeToday) thatTimeToday else thatTimeTomorrow
  }
}

@json
case class BillingCycle(months: Int) extends AnyVal

object BillingCycle {
  def months(n: Int): BillingCycle = BillingCycle(n)
}

case class PaidPlanInfo(
  id: PublicId[PaidPlan],
  name: String,
  fullName: String,
  pricePerUser: DollarAmount,
  cycle: BillingCycle,
  features: Set[Feature])
object PaidPlanInfo {
  implicit val format = (
    (__ \ 'id).format[PublicId[PaidPlan]] and
    (__ \ 'name).format[String] and
    (__ \ 'fullName).format[String] and
    (__ \ 'pricePerUser).format(DollarAmount.formatAsCents) and
    (__ \ 'cycle).format[BillingCycle] and
    (__ \ 'features).format[Set[Feature]]
  )(PaidPlanInfo.apply, unlift(PaidPlanInfo.unapply))
}

case class PaidPlan(
    id: Option[Id[PaidPlan]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[PaidPlan] = PaidPlanStates.ACTIVE,
    kind: PaidPlan.Kind,
    name: Name[PaidPlan], // as is, deprecated. need to migrate the .displayName column values over to this column, then use .name instead of .displayName in-code
    displayName: String, // not actually a display name, use fullName instead TODO: migrate this over to `.name`
    billingCycle: BillingCycle,
    pricePerCyclePerUser: DollarAmount,
    editableFeatures: Set[Feature],
    defaultSettings: OrganizationSettings) extends ModelWithPublicId[PaidPlan] with ModelWithState[PaidPlan] {

  def withId(id: Id[PaidPlan]): PaidPlan = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime): PaidPlan = this.copy(updatedAt = now)
  def withState(state: State[PaidPlan]): PaidPlan = this.copy(state = state)

  def withNewEditableFeature(f: Feature) = this.copy(editableFeatures = editableFeatures + f)
  def withNewDefaultSetting(fs: (Feature, FeatureSetting)) = this.copy(defaultSettings = defaultSettings.withFeatureSetTo(fs))

  def isActive: Boolean = state == PaidPlanStates.ACTIVE

  def asInfo(implicit config: PublicIdConfiguration): PaidPlanInfo = PaidPlanInfo(
    id = PaidPlan.publicId(id.get),
    name = displayName,
    fullName = fullName,
    pricePerUser = pricePerCyclePerUser,
    cycle = billingCycle,
    features = editableFeatures
  )

  def fullName = {
    val cycleString = billingCycle.months match {
      case 1 => "Monthly"
      case 12 => "Annual"
      case _ => "Custom"
    }
    displayName match {
      case freeName if freeName.toLowerCase.contains("free") => displayName
      case _ => displayName + " " + cycleString
    }
  }

  def isDefault = this.id.contains(PaidPlan.DEFAULT)
  def showUpsells: Boolean = PaidPlan.showUpsells(this)
}

object PaidPlan extends PublicIdGenerator[PaidPlan] {
  protected[this] val publicIdPrefix = "pp"
  protected[this] val publicIdIvSpec = new IvParameterSpec(Array(-81, 48, 82, -97, 110, 73, -46, -55, 43, 73, -107, -90, 89, 21, 116, -101))

  val DEFAULT = Id[PaidPlan](3L)
  def showUpsells(plan: PaidPlan) = Set(Id[PaidPlan](1L), Id[PaidPlan](3L), DEFAULT).contains(plan.id.get)

  @json
  case class Kind(name: String) extends AnyVal
  object Kind {
    val NORMAL = Kind("normal")
    val GRANDFATHERED = Kind("grandfathered")
    val CUSTOM = Kind("custom")
  }

}

object PaidPlanStates extends States[PaidPlan]

case class PlanEnrollment(numAccounts: Int, numActiveUsers: Int)
object PlanEnrollment {
  val empty = PlanEnrollment(0, 0)
}
