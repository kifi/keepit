package com.keepit.payments

import com.amazonaws.services.cloudfront.model.InvalidArgumentException
import com.keepit.common.db.{ States, ModelWithState, Id, State }
import com.keepit.common.crypto.{ ModelWithPublicId, ModelWithPublicIdCompanion }
import com.keepit.common.time._
import com.keepit.model.User
import com.keepit.common.mail.EmailAddress

import com.kifi.macros.json

import play.api.libs.json.{ JsValue, JsNull, Json }

import org.joda.time.DateTime

import javax.crypto.spec.IvParameterSpec

case class ActionAttribution(user: Option[Id[User]], admin: Option[Id[User]])

sealed trait AccountEventAction {
  def eventType: String
  def toDbRow: (String, JsValue)
}

object AccountEventAction { //There is probably a deeper type hierarchy that can be used here...

  trait Payloadless { self: AccountEventAction =>
    def toDbRow: (String, JsValue) = (eventType, JsNull)
  }

  case class SpecialCredit() extends AccountEventAction with Payloadless {
    def eventType: String = "special_credit"
  }

  @json
  case class PlanBilling(plan: Id[PaidPlan], cycle: BillingCycle, price: DollarAmount, activeUsers: Int, startDate: DateTime) extends AccountEventAction {
    def eventType: String = "plan_billing"
    def toDbRow: (String, JsValue) = eventType -> Json.toJson(this)
  }

  object PlanBilling {
    def from(plan: PaidPlan, account: PaidAccount): PlanBilling = {
      if (plan.id.get != account.planId) throw new InvalidArgumentException(s"Account ${account.id.get} is on plan ${account.planId}, not on plan ${plan.id.get}")
      PlanBilling(plan.id.get, plan.billingCycle, plan.pricePerCyclePerUser, account.activeUsers, account.billingCycleStart)
    }
  }

  @json
  case class LowBalanceIgnored(amount: DollarAmount) extends AccountEventAction {
    def eventType: String = "low_balance_ignored"
    def toDbRow: (String, JsValue) = eventType -> Json.toJson(this)
  }

  case class Charge() extends AccountEventAction with Payloadless {
    def eventType: String = "charge"
  }

  case class ChargeBack() extends AccountEventAction with Payloadless {
    def eventType: String = "charge_back"
  }

  @json
  case class ChargeFailure(amount: DollarAmount, code: String, message: String) extends AccountEventAction {
    def eventType: String = "charge_failure"
    def toDbRow: (String, JsValue) = eventType -> Json.toJson(this)
  }

  case class MissingPaymentMethod() extends AccountEventAction with Payloadless {
    def eventType: String = "missing_payment_method"
  }

  @json
  case class UserAdded(who: Id[User]) extends AccountEventAction {
    def eventType: String = "user_added"
    def toDbRow: (String, JsValue) = eventType -> Json.toJson(this)
  }

  @json
  case class UserRemoved(who: Id[User]) extends AccountEventAction {
    def eventType: String = "user_removed"
    def toDbRow: (String, JsValue) = eventType -> Json.toJson(this)
  }

  @json
  case class AdminAdded(who: Id[User]) extends AccountEventAction {
    def eventType: String = "admin_added"
    def toDbRow: (String, JsValue) = eventType -> Json.toJson(this)
  }

  @json
  case class AdminRemoved(who: Id[User]) extends AccountEventAction {
    def eventType: String = "admin_removed"
    def toDbRow: (String, JsValue) = eventType -> Json.toJson(this)
  }

  @json
  case class PlanChanged(oldPlan: Id[PaidPlan], newPlan: Id[PaidPlan]) extends AccountEventAction {
    def eventType: String = "plan_changed"
    def toDbRow: (String, JsValue) = eventType -> Json.toJson(this)
  }

  @json
  case class PaymentMethodAdded(id: Id[PaymentMethod], lastFour: String) extends AccountEventAction {
    def eventType: String = "payment_method_added"
    def toDbRow: (String, JsValue) = eventType -> Json.toJson(this)
  }

  @json
  case class DefaultPaymentMethodChanged(from: Option[Id[PaymentMethod]], to: Id[PaymentMethod], toLastFour: String) extends AccountEventAction {
    def eventType: String = "default_payment_method_changed"
    def toDbRow: (String, JsValue) = eventType -> Json.toJson(this)
  }

  @json
  case class AccountContactsChanged(userAdded: Option[Id[User]], userRemoved: Option[Id[User]], emailAdded: Option[EmailAddress], emailRemoved: Option[EmailAddress]) extends AccountEventAction {
    def eventType: String = "account_contacts_changed"
    def toDbRow: (String, JsValue) = eventType -> Json.toJson(this)
  }

  def fromDb(eventType: String, extras: JsValue): AccountEventAction = eventType match {
    case "special_credit" => SpecialCredit()
    case "charge_back" => ChargeBack()
    case "charge" => Charge()
    case "user_added" => extras.as[UserAdded]
    case "user_removed" => extras.as[UserRemoved]
    case "admin_added" => extras.as[AdminAdded]
    case "admin_removed" => extras.as[AdminRemoved]
    case "plan_changed" => extras.as[PlanChanged]
    case "payment_method_added" => extras.as[PaymentMethodAdded]
    case "default_payment_method_changed" => extras.as[DefaultPaymentMethodChanged]
    case "account_contacts_changed" => extras.as[AccountContactsChanged]
    case _ => throw new Exception(s"Invalid Event Type: $eventType")

  }

}

case class AccountEvent(
    id: Option[Id[AccountEvent]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[AccountEvent] = AccountEventStates.ACTIVE,
    eventTime: DateTime,
    accountId: Id[PaidAccount],
    billingRelated: Boolean,
    whoDunnit: Option[Id[User]],
    whoDunnitExtra: JsValue,
    kifiAdminInvolved: Option[Id[User]],
    action: AccountEventAction,
    creditChange: DollarAmount,
    paymentMethod: Option[Id[PaymentMethod]],
    paymentCharge: Option[DollarAmount],
    memo: Option[String],
    chargeId: Option[String]) extends ModelWithPublicId[AccountEvent] with ModelWithState[AccountEvent] {

  def withId(id: Id[AccountEvent]): AccountEvent = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime): AccountEvent = this.copy(updatedAt = now)
  def withState(state: State[AccountEvent]): AccountEvent = this.copy(state = state)
}

object AccountEvent extends ModelWithPublicIdCompanion[AccountEvent] {

  protected[this] val publicIdPrefix = "ae"
  protected[this] val publicIdIvSpec = new IvParameterSpec(Array(-57, -50, -59, -20, 87, -37, -64, 34, -84, -42, 10, 118, 40, -17, -23, -93))

  def applyFromDbRow(
    id: Option[Id[AccountEvent]],
    createdAt: DateTime,
    updatedAt: DateTime,
    state: State[AccountEvent],
    eventTime: DateTime,
    accountId: Id[PaidAccount],
    billingRelated: Boolean,
    whoDunnit: Option[Id[User]],
    whoDunnitExtra: Option[JsValue],
    kifiAdminInvolved: Option[Id[User]],
    eventType: String,
    eventTypeExtras: Option[JsValue],
    creditChange: DollarAmount,
    paymentMethod: Option[Id[PaymentMethod]],
    paymentCharge: Option[DollarAmount],
    memo: Option[String],
    chargeId: Option[String]): AccountEvent = {
    AccountEvent(
      id,
      createdAt,
      updatedAt,
      state,
      eventTime,
      accountId,
      billingRelated,
      whoDunnit,
      whoDunnitExtra getOrElse JsNull,
      kifiAdminInvolved,
      AccountEventAction.fromDb(eventType, eventTypeExtras getOrElse JsNull),
      creditChange,
      paymentMethod,
      paymentCharge,
      memo,
      chargeId
    )
  }

  def unapplyFromDbRow(e: AccountEvent) = {
    val (eventType, extras) = e.action.toDbRow
    Some((
      e.id,
      e.createdAt,
      e.updatedAt,
      e.state,
      e.eventTime,
      e.accountId,
      e.billingRelated,
      e.whoDunnit,
      if (e.whoDunnitExtra == JsNull) None else Some(e.whoDunnitExtra),
      e.kifiAdminInvolved,
      eventType,
      if (extras == JsNull) None else Some(extras),
      e.creditChange,
      e.paymentMethod,
      e.paymentCharge,
      e.memo,
      e.chargeId
    ))
  }

  def simpleNonBillingEvent(eventTime: DateTime, accountId: Id[PaidAccount], attribution: ActionAttribution, action: AccountEventAction, creditChange: DollarAmount = DollarAmount.ZERO) = {
    AccountEvent(
      eventTime = eventTime,
      accountId = accountId,
      billingRelated = false,
      whoDunnit = attribution.user,
      whoDunnitExtra = JsNull,
      kifiAdminInvolved = attribution.admin,
      action = action,
      creditChange = creditChange,
      paymentMethod = None,
      paymentCharge = None,
      memo = None,
      chargeId = None
    )
  }

}

object AccountEventStates extends States[AccountEvent]

