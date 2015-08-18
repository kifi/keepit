package com.keepit.payments

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

trait AccountEventAction {
  def eventType: String
  def toDbRow: (String, JsValue)
}

object AccountEventAction { //There is probably a deeper type hirachy that can be used here...

  trait Payloadless { self: AccountEventAction =>
    def toDbRow: (String, JsValue) = (eventType, JsNull)
  }

  case class SpecialCredit() extends AccountEventAction with Payloadless {
    def eventType: String = "special_credit"
  }

  case class ChargeBack() extends AccountEventAction with Payloadless {
    def eventType: String = "charge_back"
  }

  case class PlanBillingCredit() extends AccountEventAction with Payloadless {
    def eventType: String = "plan_billing_credit"
  }

  case class PlanBillingCharge() extends AccountEventAction with Payloadless {
    def eventType: String = "plan_billing_charge"
  }

  case class PlanBillingCreditPartial() extends AccountEventAction with Payloadless {
    def eventType: String = "plan_billing_credit_partial"
  }

  case class PlanBillingChargePartial() extends AccountEventAction with Payloadless {
    def eventType: String = "plan_billing_charge_partial"
  }

  case class PlanChangeCredit() extends AccountEventAction with Payloadless {
    def eventType: String = "plan_change_credit"
  }

  case class UserChangeCredit() extends AccountEventAction with Payloadless {
    def eventType: String = "user_change_credit"
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
  case class PaymentMethodAdded(id: Id[PaymentMethod]) extends AccountEventAction {
    def eventType: String = "payment_method_added"
    def toDbRow: (String, JsValue) = eventType -> Json.toJson(this)
  }

  @json
  case class DefaultPaymentMethodChanged(from: Option[Id[PaymentMethod]], to: Id[PaymentMethod]) extends AccountEventAction {
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
    case "plan_billing_credit" => PlanBillingCredit()
    case "plan_billing_charge" => PlanBillingCharge()
    case "plan_billing_credit_partial" => PlanBillingCreditPartial()
    case "plan_billing_charge_partial" => PlanBillingChargePartial()
    case "plan_change_credit" => PlanChangeCredit()
    case "user_change_credit" => UserChangeCredit()
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

case class EventGroup(id: String) extends AnyVal

object EventGroup {
  def apply(): EventGroup = {
    EventGroup(java.util.UUID.randomUUID.toString)
  }
}

case class AccountEvent(
    id: Option[Id[AccountEvent]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[AccountEvent] = AccountEventStates.ACTIVE,
    eventGroup: EventGroup,
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
    memo: Option[String]) extends ModelWithPublicId[AccountEvent] with ModelWithState[AccountEvent] {

  def withId(id: Id[AccountEvent]): AccountEvent = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime): AccountEvent = this.copy(updatedAt = now)
}

object AccountEvent extends ModelWithPublicIdCompanion[AccountEvent] {

  protected[this] val publicIdPrefix = "ae"
  protected[this] val publicIdIvSpec = new IvParameterSpec(Array(-57, -50, -59, -20, 87, -37, -64, 34, -84, -42, 10, 118, 40, -17, -23, -93))

  def applyFromDbRow(
    id: Option[Id[AccountEvent]],
    createdAt: DateTime,
    updatedAt: DateTime,
    state: State[AccountEvent],
    eventGroup: EventGroup,
    eventTime: DateTime,
    accountId: Id[PaidAccount],
    billingRelated: Boolean,
    whoDunnit: Option[Id[User]],
    whoDunnitExtra: JsValue,
    kifiAdminInvolved: Option[Id[User]],
    eventType: String,
    eventTypeExtras: JsValue,
    creditChange: DollarAmount,
    paymentMethod: Option[Id[PaymentMethod]],
    paymentCharge: Option[DollarAmount],
    memo: Option[String]): AccountEvent = {
    AccountEvent(
      id,
      createdAt,
      updatedAt,
      state,
      eventGroup,
      eventTime,
      accountId,
      billingRelated,
      whoDunnit,
      whoDunnitExtra,
      kifiAdminInvolved,
      AccountEventAction.fromDb(eventType, eventTypeExtras),
      creditChange,
      paymentMethod,
      paymentCharge,
      memo
    )
  }

  def unapplyFromDbRow(e: AccountEvent) = {
    val (eventType, extras) = e.action.toDbRow
    Some((e.id, e.createdAt, e.updatedAt, e.state, e.eventGroup, e.eventTime, e.accountId,
      e.billingRelated, e.whoDunnit, e.whoDunnitExtra, e.kifiAdminInvolved, eventType,
      extras, e.creditChange, e.paymentMethod, e.paymentCharge, e.memo))
  }

  def simpleNonBillingEvent(eventTime: DateTime, accountId: Id[PaidAccount], attribution: ActionAttribution, action: AccountEventAction, pending: Boolean = false) = {
    AccountEvent(
      state = if (pending) AccountEventStates.PENDING else AccountEventStates.ACTIVE,
      eventGroup = EventGroup(),
      eventTime = eventTime,
      accountId = accountId,
      billingRelated = false,
      whoDunnit = attribution.user,
      whoDunnitExtra = JsNull,
      kifiAdminInvolved = attribution.admin,
      action = action,
      creditChange = DollarAmount(0),
      paymentMethod = None,
      paymentCharge = None,
      memo = None
    )
  }
}

object AccountEventStates extends States[AccountEvent] {
  val PENDING = State[AccountEvent]("pending")
  val PROCESSING = State[AccountEvent]("processing")
  val FAILED = State[AccountEvent]("failed")
}
