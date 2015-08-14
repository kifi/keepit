package com.keepit.payments

import com.keepit.common.logging.Logging
import com.keepit.common.db.slick.Database
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.Id
import com.keepit.common.time._
import com.keepit.model.{ Organization, User, Name }
import com.keepit.common.mail.EmailAddress

import scala.concurrent.ExecutionContext

import play.api.libs.json.JsNull

import com.google.inject.{ ImplementedBy, Inject }

import org.joda.time.DateTime

class UnauthorizedChange(msg: String) extends Exception(msg)
class InvalidChange(msg: String) extends Exception

@ImplementedBy(classOf[PlanManagementCommanderImpl])
trait PlanManagementCommander {
  def createPaidAccountForOrganization(orgId: Id[Organization], planId: Id[PaidPlan]): Unit

  def registerNewUser(orgId: Id[Organization], userId: Id[User], attribution: ActionAttribution): Unit
  def registerRemovedUser(orgId: Id[Organization], userId: Id[User], attribution: ActionAttribution): Unit
  def registerNewAdmin(orgId: Id[Organization], userId: Id[User], attribution: ActionAttribution): Unit
  def registerRemovedAdmin(orgId: Id[Organization], userId: Id[User], attribution: ActionAttribution): Unit

  def removeUserAccountContact(orgId: Id[Organization], userId: Id[User], attribution: ActionAttribution): Unit
  def removeEmailAccountContact(orgId: Id[Organization], emailAddress: EmailAddress, attribution: ActionAttribution): Unit
  def addUserAccountContact(orgId: Id[Organization], userId: Id[User], attribution: ActionAttribution): Unit
  def addEmailAccountContact(orgId: Id[Organization], emailAddress: EmailAddress, attribution: ActionAttribution): Unit
  def getAccountContacts(orgId: Id[Organization]): (Seq[Id[User]], Seq[EmailAddress])

  def grantSpecialCredit(orgId: Id[Organization], amount: DollarAmount, adminWho: Option[Id[User]], memo: Option[String]): Unit
  def getCurrentCredit(orgId: Id[Organization]): DollarAmount

  def createNewPlan(name: Name[PaidPlan], billingCycle: BillingCycle, price: DollarAmount, custom: Boolean = false): PaidPlan
  def grandfatherPlan(id: Id[PaidPlan]): Unit
  def deactivatePlan(id: Id[PaidPlan]): Unit

  def getAvailablePlans(adminWho: Option[Id[User]] = None): Seq[PaidPlan]
  def changePlan(orgId: Id[Organization], newPlan: Id[PaidPlan], attribution: ActionAttribution): Unit

  def getActivePaymentMethods(orgId: Id[Organization]): Seq[PaymentMethod]
  def addPaymentMethod(orgId: Id[Organization], stripeToken: StripeToken, attribution: ActionAttribution): Unit
  def changeDefaultPaymentMethod(orgId: Id[Organization], newDefault: Id[PaymentMethod], attribution: ActionAttribution): Unit

  def getAccountEvents(orgId: Id[Organization], before: Option[DateTime], max: Int, billingFilter: Option[Boolean]): Seq[AccountEvent]
}

class PlanManagementCommanderImpl @Inject() (
  db: Database,
  paymentMethodRepo: PaymentMethodRepo,
  accountEventRepo: AccountEventRepo,
  paidAccountRepo: PaidAccountRepo,
  paidPlanRepo: PaidPlanRepo,
  implicit val defaultContext: ExecutionContext)
    extends PlanManagementCommander with Logging {

  private def orgId2AccountId(orgId: Id[Organization])(implicit session: RSession): Id[PaidAccount] = {
    paidAccountRepo.getAccountId(orgId)
  }

  def createPaidAccountForOrganization(orgId: Id[Organization], planId: Id[PaidPlan]): Unit = {
    db.readWrite { implicit session =>
      val plan = paidPlanRepo.get(planId)
      if (plan.state != PaidPlanStates.ACTIVE) {
        throw new InvalidChange("plan_not_active")
      }
      paidAccountRepo.getByOrgId(orgId, Set()) match {
        case Some(pa) if pa.state == PaidAccountStates.ACTIVE => throw new InvalidChange("account_exists")
        case Some(pa) => paidAccountRepo.save(pa.copy(state = PaidAccountStates.ACTIVE, planId = planId))
        case None => paidAccountRepo.save(PaidAccount(
          orgId = orgId,
          planId = planId,
          credit = DollarAmount(0),
          userContacts = Seq.empty,
          emailContacts = Seq.empty
        ))
      }
    }
  }

  def registerNewUser(orgId: Id[Organization], userId: Id[User], attribution: ActionAttribution): Unit = db.readWrite { implicit session =>
    accountEventRepo.save(AccountEvent(
      state = AccountEventStates.PENDING,
      eventGroup = EventGroup(),
      eventTime = currentDateTime,
      accountId = orgId2AccountId(orgId),
      billingRelated = false,
      whoDunnit = attribution.user,
      whoDunnitExtra = JsNull,
      kifiAdminInvolved = attribution.admin,
      action = AccountEventAction.UserAdded(userId),
      creditChange = DollarAmount(0),
      paymentMethod = None,
      paymentCharge = None,
      memo = None
    ))
  }

  def registerRemovedUser(orgId: Id[Organization], userId: Id[User], attribution: ActionAttribution): Unit = db.readWrite { implicit session =>
    accountEventRepo.save(AccountEvent(
      state = AccountEventStates.PENDING,
      eventGroup = EventGroup(),
      eventTime = currentDateTime,
      accountId = orgId2AccountId(orgId),
      billingRelated = false,
      whoDunnit = attribution.user,
      whoDunnitExtra = JsNull,
      kifiAdminInvolved = attribution.admin,
      action = AccountEventAction.UserRemoved(userId),
      creditChange = DollarAmount(0),
      paymentMethod = None,
      paymentCharge = None,
      memo = None
    ))
  }

  def registerNewAdmin(orgId: Id[Organization], userId: Id[User], attribution: ActionAttribution): Unit = db.readWrite { implicit session =>
    accountEventRepo.save(AccountEvent(
      eventGroup = EventGroup(),
      eventTime = currentDateTime,
      accountId = orgId2AccountId(orgId),
      billingRelated = false,
      whoDunnit = attribution.user,
      whoDunnitExtra = JsNull,
      kifiAdminInvolved = attribution.admin,
      action = AccountEventAction.AdminAdded(userId),
      creditChange = DollarAmount(0),
      paymentMethod = None,
      paymentCharge = None,
      memo = None
    ))
  }

  def registerRemovedAdmin(orgId: Id[Organization], userId: Id[User], attribution: ActionAttribution): Unit = db.readWrite { implicit session =>
    accountEventRepo.save(AccountEvent(
      eventGroup = EventGroup(),
      eventTime = currentDateTime,
      accountId = orgId2AccountId(orgId),
      billingRelated = false,
      whoDunnit = attribution.user,
      whoDunnitExtra = JsNull,
      kifiAdminInvolved = attribution.admin,
      action = AccountEventAction.AdminRemoved(userId),
      creditChange = DollarAmount(0),
      paymentMethod = None,
      paymentCharge = None,
      memo = None
    ))
  }

  def removeUserAccountContact(orgId: Id[Organization], userId: Id[User], attribution: ActionAttribution): Unit = db.readWrite { implicit session =>
    val updatedAccount = paidAccountRepo.getByOrgId(orgId) match {
      case Some(account) => {
        account.copy(userContacts = account.userContacts.filter(_ != userId))
      }
      case None => throw new InvalidChange("account_does_not_exists")
    }
    paidAccountRepo.save(updatedAccount)
    accountEventRepo.save(AccountEvent(
      eventGroup = EventGroup(),
      eventTime = currentDateTime,
      accountId = updatedAccount.id.get,
      billingRelated = false,
      whoDunnit = attribution.user,
      whoDunnitExtra = JsNull,
      kifiAdminInvolved = attribution.admin,
      action = AccountEventAction.AccountContactsChanged(userAdded = None, userRemoved = Some(userId), emailAdded = None, emailRemoved = None),
      creditChange = DollarAmount(0),
      paymentMethod = None,
      paymentCharge = None,
      memo = None
    ))
  }

  def removeEmailAccountContact(orgId: Id[Organization], emailAddress: EmailAddress, attribution: ActionAttribution): Unit = db.readWrite { implicit session =>
    val updatedAccount = paidAccountRepo.getByOrgId(orgId) match {
      case Some(account) => {
        account.copy(emailContacts = account.emailContacts.filter(_ != emailAddress))
      }
      case None => throw new InvalidChange("account_does_not_exists")
    }
    paidAccountRepo.save(updatedAccount)
    accountEventRepo.save(AccountEvent(
      eventGroup = EventGroup(),
      eventTime = currentDateTime,
      accountId = updatedAccount.id.get,
      billingRelated = false,
      whoDunnit = attribution.user,
      whoDunnitExtra = JsNull,
      kifiAdminInvolved = attribution.admin,
      action = AccountEventAction.AccountContactsChanged(userAdded = None, userRemoved = None, emailAdded = None, emailRemoved = Some(emailAddress)),
      creditChange = DollarAmount(0),
      paymentMethod = None,
      paymentCharge = None,
      memo = None
    ))
  }

  def addUserAccountContact(orgId: Id[Organization], userId: Id[User], attribution: ActionAttribution): Unit = db.readWrite { implicit session =>
    val updatedAccount = paidAccountRepo.getByOrgId(orgId) match {
      case Some(account) => {
        account.copy(userContacts = account.userContacts.filter(_ != userId) :+ userId)
      }
      case None => throw new InvalidChange("account_does_not_exists")
    }
    paidAccountRepo.save(updatedAccount)
    accountEventRepo.save(AccountEvent(
      eventGroup = EventGroup(),
      eventTime = currentDateTime,
      accountId = updatedAccount.id.get,
      billingRelated = false,
      whoDunnit = attribution.user,
      whoDunnitExtra = JsNull,
      kifiAdminInvolved = attribution.admin,
      action = AccountEventAction.AccountContactsChanged(userAdded = Some(userId), userRemoved = None, emailAdded = None, emailRemoved = None),
      creditChange = DollarAmount(0),
      paymentMethod = None,
      paymentCharge = None,
      memo = None
    ))
  }

  def addEmailAccountContact(orgId: Id[Organization], emailAddress: EmailAddress, attribution: ActionAttribution): Unit = db.readWrite { implicit session =>
    val updatedAccount = paidAccountRepo.getByOrgId(orgId) match {
      case Some(account) => {
        account.copy(emailContacts = account.emailContacts.filter(_ != emailAddress) :+ emailAddress)
      }
      case None => throw new InvalidChange("account_does_not_exists")
    }
    paidAccountRepo.save(updatedAccount)
    accountEventRepo.save(AccountEvent(
      eventGroup = EventGroup(),
      eventTime = currentDateTime,
      accountId = updatedAccount.id.get,
      billingRelated = false,
      whoDunnit = attribution.user,
      whoDunnitExtra = JsNull,
      kifiAdminInvolved = attribution.admin,
      action = AccountEventAction.AccountContactsChanged(userAdded = None, userRemoved = None, emailAdded = Some(emailAddress), emailRemoved = None),
      creditChange = DollarAmount(0),
      paymentMethod = None,
      paymentCharge = None,
      memo = None
    ))
  }

  def getAccountContacts(orgId: Id[Organization]): (Seq[Id[User]], Seq[EmailAddress]) = db.readOnlyMaster { implicit session =>
    paidAccountRepo.getByOrgId(orgId) match {
      case Some(account) => {
        (account.userContacts, account.emailContacts)
      }
      case None => throw new Exception("account_does_not_exists")
    }
  }

  def grantSpecialCredit(orgId: Id[Organization], amount: DollarAmount, adminWho: Option[Id[User]], memo: Option[String]): Unit = db.readWrite { implicit session =>
    val account = paidAccountRepo.getByOrgId(orgId) match {
      case Some(account) => account
      case None => throw new InvalidChange("account_does_not_exists")
    }
    paidAccountRepo.save(account.copy(credit = account.credit + amount))
    accountEventRepo.save(AccountEvent(
      eventGroup = EventGroup(),
      eventTime = currentDateTime,
      accountId = account.id.get,
      billingRelated = false,
      whoDunnit = None,
      whoDunnitExtra = JsNull,
      kifiAdminInvolved = adminWho,
      action = AccountEventAction.SpecialCredit(),
      creditChange = amount,
      paymentMethod = None,
      paymentCharge = None,
      memo = memo
    ))
  }

  def getCurrentCredit(orgId: Id[Organization]): DollarAmount = db.readOnlyMaster { implicit session =>
    val account = paidAccountRepo.getByOrgId(orgId) match {
      case Some(account) => account
      case None => throw new Exception("account_does_not_exists")
    }
    account.credit
  }

  def createNewPlan(name: Name[PaidPlan], billingCycle: BillingCycle, price: DollarAmount, custom: Boolean = false): PaidPlan = db.readWrite { implicit session =>
    paidPlanRepo.save(PaidPlan(
      state = if (custom) PaidPlanStates.CUSTOM else PaidPlanStates.ACTIVE,
      name = name,
      billingCycle = billingCycle,
      pricePerCyclePerUser = price
    ))
  }

  def grandfatherPlan(id: Id[PaidPlan]): Unit = db.readWrite { implicit session =>
    val plan = paidPlanRepo.get(id)
    if (plan.state == PaidPlanStates.ACTIVE) {
      paidPlanRepo.save(plan.copy(state = PaidPlanStates.GRANDFATHERED))
    } else {
      throw new InvalidChange("plan_not_active")
    }
  }

  def deactivatePlan(id: Id[PaidPlan]): Unit = db.readWrite { implicit session =>
    val plan = paidPlanRepo.get(id)
    val accounts = paidAccountRepo.getActiveByPlan(id)
    if (accounts.isEmpty) {
      paidPlanRepo.save(plan.copy(state = PaidPlanStates.INACTIVE))
    } else {
      throw new InvalidChange("plan_in_use")
    }
  }

  def getAvailablePlans(adminWho: Option[Id[User]] = None): Seq[PaidPlan] = db.readOnlyMaster { implicit session =>
    val states = if (adminWho.isDefined) Set(PaidPlanStates.ACTIVE, PaidPlanStates.CUSTOM) else Set(PaidPlanStates.ACTIVE)
    paidPlanRepo.getByStates(states)
  }

  def changePlan(orgId: Id[Organization], newPlanId: Id[PaidPlan], attribution: ActionAttribution): Unit = db.readWrite { implicit session =>
    val account = paidAccountRepo.getByOrgId(orgId) match {
      case Some(account) if account.state == PaidAccountStates.ACTIVE => account
      case _ => throw new InvalidChange("account_does_not_exists")
    }
    val newPlan = paidPlanRepo.get(newPlanId)
    if (newPlan.state == PaidPlanStates.ACTIVE || (newPlan.state == PaidPlanStates.CUSTOM && attribution.admin.isDefined)) {
      paidAccountRepo.save(account.copy(planId = newPlanId))
    } else {
      throw new InvalidChange("plan_not_available")
    }
    accountEventRepo.save(AccountEvent(
      state = AccountEventStates.PENDING,
      eventGroup = EventGroup(),
      eventTime = currentDateTime,
      accountId = account.id.get,
      billingRelated = false,
      whoDunnit = attribution.user,
      whoDunnitExtra = JsNull,
      kifiAdminInvolved = attribution.admin,
      action = AccountEventAction.PlanChanged(account.planId, newPlanId),
      creditChange = DollarAmount(0),
      paymentMethod = None,
      paymentCharge = None,
      memo = None
    ))
  }

  def getActivePaymentMethods(orgId: Id[Organization]): Seq[PaymentMethod] = db.readOnlyMaster { implicit session =>
    paymentMethodRepo.getByAccountId(orgId2AccountId(orgId))
  }

  def addPaymentMethod(orgId: Id[Organization], stripeToken: StripeToken, attribution: ActionAttribution): Unit = db.readWrite { implicit session =>
    val accountId = orgId2AccountId(orgId)
    val newPaymentMethod = paymentMethodRepo.save(PaymentMethod(
      accountId = accountId,
      default = false,
      stripeToken = stripeToken
    ))
    accountEventRepo.save(AccountEvent(
      eventGroup = EventGroup(),
      eventTime = currentDateTime,
      accountId = accountId,
      billingRelated = false,
      whoDunnit = attribution.user,
      whoDunnitExtra = JsNull,
      kifiAdminInvolved = attribution.admin,
      action = AccountEventAction.PaymentMethodAdded(newPaymentMethod.id.get),
      creditChange = DollarAmount(0),
      paymentMethod = None,
      paymentCharge = None,
      memo = None
    ))
  }

  def changeDefaultPaymentMethod(orgId: Id[Organization], newDefaultId: Id[PaymentMethod], attribution: ActionAttribution): Unit = db.readWrite { implicit session =>
    val accountId = orgId2AccountId(orgId)
    val newDefault = paymentMethodRepo.get(newDefaultId)
    val oldDefaultOpt = paymentMethodRepo.getDefault(accountId)
    if (newDefault.state != PaymentMethodStates.ACTIVE) {
      throw new InvalidChange("payment_method_not_available")
    } else {
      oldDefaultOpt.map { oldDefault =>
        paymentMethodRepo.save(oldDefault.copy(default = false))
      }
      paymentMethodRepo.save(newDefault.copy(default = true))
    }
    accountEventRepo.save(AccountEvent(
      eventGroup = EventGroup(),
      eventTime = currentDateTime,
      accountId = accountId,
      billingRelated = false,
      whoDunnit = attribution.user,
      whoDunnitExtra = JsNull,
      kifiAdminInvolved = attribution.admin,
      action = AccountEventAction.DefaultPaymentMethodChanged(oldDefaultOpt.map(_.id.get), newDefault.id.get),
      creditChange = DollarAmount(0),
      paymentMethod = None,
      paymentCharge = None,
      memo = None
    ))
  }

  def getAccountEvents(orgId: Id[Organization], beforeOpt: Option[DateTime], limit: Int, billingFilter: Option[Boolean]): Seq[AccountEvent] = db.readOnlyMaster { implicit session =>
    val accountId = orgId2AccountId(orgId)
    beforeOpt.map { before =>
      accountEventRepo.getEventsBefore(accountId, before, limit, billingFilter)
    } getOrElse {
      accountEventRepo.getEvents(accountId, limit, billingFilter)
    }
  }

}

