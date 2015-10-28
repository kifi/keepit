package com.keepit.payments

import javax.crypto.spec.IvParameterSpec

import com.amazonaws.services.cloudfront.model.InvalidArgumentException
import com.keepit.common.crypto.{ ModelWithPublicId, ModelWithPublicIdCompanion }
import com.keepit.common.db.{ Id, ModelWithState, State, States }
import com.keepit.common.mail.EmailAddress
import com.keepit.common.time._
import com.keepit.model.User
import com.keepit.common.mail.EmailAddress
import com.kifi.macros.json
import org.joda.time.DateTime
import play.api.libs.json.{ JsNull, JsValue, Json }

case class ActionAttribution(user: Option[Id[User]], admin: Option[Id[User]])

sealed abstract class AccountEventKind(val value: String)
object AccountEventKind {
  case object AccountContactsChanged extends AccountEventKind("account_contacts_changed")
  case object AdminAdded extends AccountEventKind("admin_added")
  case object AdminRemoved extends AccountEventKind("admin_removed")
  case object Charge extends AccountEventKind("charge")
  case object ChargeBack extends AccountEventKind("charge_back")
  case object ChargeFailure extends AccountEventKind("charge_failure")
  case object DefaultPaymentMethodChanged extends AccountEventKind("default_payment_method_changed")
  case object IntegrityError extends AccountEventKind("integrity_error")
  case object LowBalanceIgnored extends AccountEventKind("low_balance_ignored")
  case object MissingPaymentMethod extends AccountEventKind("missing_payment_method")
  case object OrganizationCreated extends AccountEventKind("organization_created")
  case object PlanRenewal extends AccountEventKind("plan_renewal")
  case object PlanChanged extends AccountEventKind("plan_changed")
  case object PaymentMethodAdded extends AccountEventKind("payment_method_added")
  case object SpecialCredit extends AccountEventKind("special_credit")
  case object RewardCredit extends AccountEventKind("reward_credit")
  case object UserAdded extends AccountEventKind("user_added")
  case object UserRemoved extends AccountEventKind("user_removed")

  val all: Set[AccountEventKind] = Set(
    AccountContactsChanged,
    AdminAdded,
    AdminRemoved,
    Charge,
    ChargeBack,
    ChargeFailure,
    DefaultPaymentMethodChanged,
    IntegrityError,
    LowBalanceIgnored,
    MissingPaymentMethod,
    OrganizationCreated,
    PaymentMethodAdded,
    PlanRenewal,
    PlanChanged,
    RewardCredit,
    SpecialCredit,
    UserAdded,
    UserRemoved
  )
  def get(str: String): Option[AccountEventKind] = all.find(_.value == str)

  val activityLog: Set[AccountEventKind] = Set(
    OrganizationCreated,
    Charge,
    ChargeBack,
    ChargeFailure,
    DefaultPaymentMethodChanged,
    PlanRenewal,
    PlanChanged,
    RewardCredit,
    SpecialCredit,
    UserAdded,
    UserRemoved,
    AdminAdded,
    AdminRemoved
  )

  val billing: Set[AccountEventKind] = Set(
    Charge,
    ChargeBack,
    ChargeFailure,
    DefaultPaymentMethodChanged,
    IntegrityError,
    LowBalanceIgnored,
    MissingPaymentMethod,
    PaymentMethodAdded,
    PlanRenewal,
    PlanChanged,
    SpecialCredit,
    UserAdded,
    UserRemoved
  )
}

sealed trait AccountEventAction {
  def eventType: AccountEventKind
  def toDbRow: (AccountEventKind, JsValue)
}

object AccountEventAction { //There is probably a deeper type hierarchy that can be used here...

  private implicit val dollarFormat = DollarAmount.formatAsCents

  trait Payloadless { self: AccountEventAction =>
    def toDbRow: (AccountEventKind, JsValue) = (eventType, JsNull)
  }

  case class SpecialCredit() extends AccountEventAction with Payloadless {
    def eventType = AccountEventKind.SpecialCredit
  }

  @json
  case class RewardCredit(rewardId: Id[CreditReward]) extends AccountEventAction {
    def eventType = AccountEventKind.RewardCredit
    def toDbRow = eventType -> Json.toJson(this)
  }

  @json
  case class PlanRenewal(plan: Id[PaidPlan], cycle: BillingCycle, price: DollarAmount, activeUsers: Int, renewalDate: DateTime) extends AccountEventAction {
    def eventType = AccountEventKind.PlanRenewal
    def toDbRow = eventType -> Json.toJson(this)
  }

  object PlanRenewal {
    def from(plan: PaidPlan, account: PaidAccount): PlanRenewal = {
      if (plan.id.get != account.planId) throw new InvalidArgumentException(s"Account ${account.id.get} is on plan ${account.planId}, not on plan ${plan.id.get}")
      PlanRenewal(plan.id.get, plan.billingCycle, plan.pricePerCyclePerUser, account.activeUsers, account.planRenewal)
    }
  }

  @json
  case class LowBalanceIgnored(amount: DollarAmount) extends AccountEventAction {
    def eventType = AccountEventKind.LowBalanceIgnored
    def toDbRow = eventType -> Json.toJson(this)
  }

  case class Charge() extends AccountEventAction with Payloadless {
    def eventType = AccountEventKind.Charge
  }

  case class ChargeBack() extends AccountEventAction with Payloadless {
    def eventType = AccountEventKind.ChargeBack
  }

  @json
  case class ChargeFailure(amount: DollarAmount, code: String, message: String) extends AccountEventAction {
    def eventType = AccountEventKind.ChargeFailure
    def toDbRow = eventType -> Json.toJson(this)
  }

  case class MissingPaymentMethod() extends AccountEventAction with Payloadless {
    def eventType = AccountEventKind.MissingPaymentMethod
  }

  @json
  case class UserAdded(who: Id[User]) extends AccountEventAction {
    def eventType = AccountEventKind.UserAdded
    def toDbRow = eventType -> Json.toJson(this)
  }

  @json
  case class UserRemoved(who: Id[User]) extends AccountEventAction {
    def eventType = AccountEventKind.UserRemoved
    def toDbRow = eventType -> Json.toJson(this)
  }

  @json
  case class AdminAdded(who: Id[User]) extends AccountEventAction {
    def eventType = AccountEventKind.AdminAdded
    def toDbRow = eventType -> Json.toJson(this)
  }

  @json
  case class AdminRemoved(who: Id[User]) extends AccountEventAction {
    def eventType = AccountEventKind.AdminRemoved
    def toDbRow = eventType -> Json.toJson(this)
  }

  @json // todo(Léo): remove Option when old events have been cleared
  case class PlanChanged(oldPlan: Id[PaidPlan], newPlan: Id[PaidPlan], startDate: Option[DateTime]) extends AccountEventAction {
    def eventType = AccountEventKind.PlanChanged
    def toDbRow = eventType -> Json.toJson(this)
  }

  @json
  case class PaymentMethodAdded(id: Id[PaymentMethod], lastFour: String) extends AccountEventAction {
    def eventType = AccountEventKind.PaymentMethodAdded
    def toDbRow = eventType -> Json.toJson(this)
  }

  @json
  case class DefaultPaymentMethodChanged(from: Option[Id[PaymentMethod]], to: Id[PaymentMethod], toLastFour: String) extends AccountEventAction {
    def eventType = AccountEventKind.DefaultPaymentMethodChanged
    def toDbRow = eventType -> Json.toJson(this)
  }

  @json
  case class AccountContactsChanged(userAdded: Option[Id[User]], userRemoved: Option[Id[User]], emailAdded: Option[EmailAddress], emailRemoved: Option[EmailAddress]) extends AccountEventAction {
    def eventType = AccountEventKind.AccountContactsChanged
    def toDbRow = eventType -> Json.toJson(this)
  }

  @json // todo(Léo): remove Option when old events have been cleared
  case class OrganizationCreated(initialPlan: Id[PaidPlan], planStartDate: Option[DateTime]) extends AccountEventAction {
    def eventType = AccountEventKind.OrganizationCreated
    def toDbRow = eventType -> Json.toJson(this)
  }

  @json
  case class IntegrityError(err: PaymentsIntegrityError) extends AccountEventAction {
    def eventType = AccountEventKind.IntegrityError
    def toDbRow = eventType -> Json.toJson(this)
  }

  def fromDb(eventType: AccountEventKind, extras: JsValue): AccountEventAction = eventType match {
    case AccountEventKind.SpecialCredit => SpecialCredit()
    case AccountEventKind.RewardCredit => extras.as[RewardCredit]
    case AccountEventKind.PlanRenewal => extras.as[PlanRenewal]
    case AccountEventKind.LowBalanceIgnored => extras.as[LowBalanceIgnored]
    case AccountEventKind.Charge => Charge()
    case AccountEventKind.ChargeBack => ChargeBack()
    case AccountEventKind.ChargeFailure => extras.as[ChargeFailure]
    case AccountEventKind.IntegrityError => extras.as[IntegrityError]
    case AccountEventKind.MissingPaymentMethod => MissingPaymentMethod()
    case AccountEventKind.UserAdded => extras.as[UserAdded]
    case AccountEventKind.UserRemoved => extras.as[UserRemoved]
    case AccountEventKind.AdminAdded => extras.as[AdminAdded]
    case AccountEventKind.AdminRemoved => extras.as[AdminRemoved]
    case AccountEventKind.PlanChanged => extras.as[PlanChanged]
    case AccountEventKind.PaymentMethodAdded => extras.as[PaymentMethodAdded]
    case AccountEventKind.DefaultPaymentMethodChanged => extras.as[DefaultPaymentMethodChanged]
    case AccountEventKind.AccountContactsChanged => extras.as[AccountContactsChanged]
    case AccountEventKind.OrganizationCreated => extras.as[OrganizationCreated]
  }

}

case class AccountEvent(
    id: Option[Id[AccountEvent]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[AccountEvent] = AccountEventStates.ACTIVE,
    eventTime: DateTime,
    accountId: Id[PaidAccount],
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
    whoDunnit: Option[Id[User]],
    whoDunnitExtra: Option[JsValue],
    kifiAdminInvolved: Option[Id[User]],
    eventType: AccountEventKind,
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

  def fromIntegrityError(accountId: Id[PaidAccount], err: PaymentsIntegrityError): AccountEvent = {
    AccountEvent(
      eventTime = currentDateTime,
      accountId = accountId,
      whoDunnit = None,
      whoDunnitExtra = JsNull,
      kifiAdminInvolved = None,
      action = AccountEventAction.IntegrityError(err),
      creditChange = DollarAmount.ZERO,
      paymentMethod = None,
      paymentCharge = None,
      memo = None,
      chargeId = None
    )
  }
}

object AccountEventStates extends States[AccountEvent]

