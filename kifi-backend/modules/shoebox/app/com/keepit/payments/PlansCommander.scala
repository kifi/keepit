package com.keepit.payments

import com.keepit.common.logging.Logging
import com.keepit.common.db.slick.Database
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import com.keepit.common.db.Id
import com.keepit.common.time._
import com.keepit.model.{ Organization, User, Name }
import com.keepit.common.mail.EmailAddress

import scala.concurrent.ExecutionContext
import scala.util.{ Try, Success, Failure }

import play.api.libs.json.JsNull

import com.google.inject.{ ImplementedBy, Inject }

import org.joda.time.DateTime

abstract class PlanManagementException(msg: String) extends Exception(msg)
class UnauthorizedChange(msg: String) extends PlanManagementException(msg)
class InvalidChange(msg: String) extends PlanManagementException(msg)

@ImplementedBy(classOf[PlanManagementCommanderImpl])
trait PlanManagementCommander {
  def createAndInitializePaidAccountForOrganization(orgId: Id[Organization], planId: Id[PaidPlan], creator: Id[User], session: RWSession): Try[AccountEvent]
  def deactivatePaidAccountForOrganziation(orgId: Id[Organization], session: RWSession): Try[Unit]

  def registerNewUser(orgId: Id[Organization], userId: Id[User], attribution: ActionAttribution): AccountEvent
  def registerRemovedUser(orgId: Id[Organization], userId: Id[User], attribution: ActionAttribution): AccountEvent
  def registerNewAdmin(orgId: Id[Organization], userId: Id[User], attribution: ActionAttribution): AccountEvent
  def registerRemovedAdmin(orgId: Id[Organization], userId: Id[User], attribution: ActionAttribution): AccountEvent

  def removeUserAccountContact(orgId: Id[Organization], userId: Id[User], attribution: ActionAttribution): AccountEvent
  def removeEmailAccountContact(orgId: Id[Organization], emailAddress: EmailAddress, attribution: ActionAttribution): AccountEvent
  def addUserAccountContact(orgId: Id[Organization], userId: Id[User], attribution: ActionAttribution): AccountEvent
  def addEmailAccountContact(orgId: Id[Organization], emailAddress: EmailAddress, attribution: ActionAttribution): AccountEvent
  def getAccountContacts(orgId: Id[Organization]): (Seq[Id[User]], Seq[EmailAddress])

  def grantSpecialCredit(orgId: Id[Organization], amount: DollarAmount, grantedByAdmin: Option[Id[User]], memo: Option[String]): AccountEvent
  def getCurrentCredit(orgId: Id[Organization]): DollarAmount

  def createNewPlan(name: Name[PaidPlan], billingCycle: BillingCycle, price: DollarAmount, custom: Boolean = false): PaidPlan
  def grandfatherPlan(id: Id[PaidPlan]): Try[PaidPlan]
  def deactivatePlan(id: Id[PaidPlan]): Try[PaidPlan]

  def getAvailablePlans(grantedByAdmin: Option[Id[User]] = None): Seq[PaidPlan]
  def changePlan(orgId: Id[Organization], newPlan: Id[PaidPlan], attribution: ActionAttribution): Try[AccountEvent]

  def getActivePaymentMethods(orgId: Id[Organization]): Seq[PaymentMethod]
  def addPaymentMethod(orgId: Id[Organization], stripeToken: StripeToken, attribution: ActionAttribution): AccountEvent
  def changeDefaultPaymentMethod(orgId: Id[Organization], newDefault: Id[PaymentMethod], attribution: ActionAttribution): Try[AccountEvent]

  def getAccountEvents(orgId: Id[Organization], before: Option[DateTime], max: Int, onlyRelatedToBilling: Option[Boolean]): Seq[AccountEvent]
}

class PlanManagementCommanderImpl @Inject() (
  db: Database,
  paymentMethodRepo: PaymentMethodRepo,
  accountEventRepo: AccountEventRepo,
  paidAccountRepo: PaidAccountRepo,
  paidPlanRepo: PaidPlanRepo,
  clock: Clock,
  implicit val defaultContext: ExecutionContext)
    extends PlanManagementCommander with Logging {

  private def orgId2AccountId(orgId: Id[Organization])(implicit session: RSession): Id[PaidAccount] = {
    paidAccountRepo.getAccountId(orgId)
  }

  //very explicitely accepts a db session to allow account creation on org creation within the same db session
  def createAndInitializePaidAccountForOrganization(orgId: Id[Organization], planId: Id[PaidPlan], creator: Id[User], session: RWSession): Try[AccountEvent] = {
    implicit val s = session
    val plan = paidPlanRepo.get(planId)
    if (plan.state != PaidPlanStates.ACTIVE) {
      Failure(new InvalidChange("plan_not_active"))
    } else {
      val maybeAccount = paidAccountRepo.maybeGetByOrgId(orgId, Set()) match {
        case Some(pa) if pa.state == PaidAccountStates.ACTIVE => Failure(new InvalidChange("account_exists"))
        case Some(pa) =>
          Success(paidAccountRepo.save(PaidAccount(
            id = pa.id,
            orgId = orgId,
            planId = planId,
            credit = DollarAmount(0),
            userContacts = Seq.empty,
            emailContacts = Seq.empty
          )))
        case None =>
          Success(paidAccountRepo.save(PaidAccount(
            orgId = orgId,
            planId = planId,
            credit = DollarAmount(0),
            userContacts = Seq.empty,
            emailContacts = Seq.empty
          )))
      }
      maybeAccount.map { account =>
        accountEventRepo.save(AccountEvent.simpleNonBillingEvent(
          eventTime = clock.now,
          accountId = account.id.get,
          attribution = ActionAttribution(user = Some(creator), admin = None),
          action = AccountEventAction.UserAdded(creator),
          pending = true
        ))
        accountEventRepo.save(AccountEvent.simpleNonBillingEvent(
          eventTime = clock.now,
          accountId = account.id.get,
          attribution = ActionAttribution(user = Some(creator), admin = None),
          action = AccountEventAction.AdminAdded(creator)
        ))
      }
    }
  }

  def deactivatePaidAccountForOrganziation(orgId: Id[Organization], session: RWSession): Try[Unit] = {
    implicit val s = session
    Try {
      paidAccountRepo.maybeGetByOrgId(orgId).foreach { account =>
        paidAccountRepo.save(account.withState(PaidAccountStates.INACTIVE))
        accountEventRepo.inactivateAll(account.id.get)
        paymentMethodRepo.getByAccountId(account.id.get).foreach { paymentMethod =>
          paymentMethodRepo.save(paymentMethod.copy(
            state = PaymentMethodStates.INACTIVE,
            default = false,
            stripeToken = StripeToken.DELETED
          ))
        }
      }
    }

  }

  def registerNewUser(orgId: Id[Organization], userId: Id[User], attribution: ActionAttribution): AccountEvent = db.readWrite { implicit session =>
    accountEventRepo.save(AccountEvent.simpleNonBillingEvent(
      eventTime = clock.now,
      accountId = orgId2AccountId(orgId),
      attribution = attribution,
      action = AccountEventAction.UserAdded(userId),
      pending = true
    ))
  }

  def registerRemovedUser(orgId: Id[Organization], userId: Id[User], attribution: ActionAttribution): AccountEvent = db.readWrite { implicit session =>
    accountEventRepo.save(AccountEvent.simpleNonBillingEvent(
      eventTime = clock.now,
      accountId = orgId2AccountId(orgId),
      attribution = attribution,
      action = AccountEventAction.UserRemoved(userId),
      pending = true
    ))
  }

  def registerNewAdmin(orgId: Id[Organization], userId: Id[User], attribution: ActionAttribution): AccountEvent = db.readWrite { implicit session =>
    accountEventRepo.save(AccountEvent.simpleNonBillingEvent(
      eventTime = clock.now,
      accountId = orgId2AccountId(orgId),
      attribution = attribution,
      action = AccountEventAction.AdminAdded(userId)
    ))
  }

  def registerRemovedAdmin(orgId: Id[Organization], userId: Id[User], attribution: ActionAttribution): AccountEvent = db.readWrite { implicit session =>
    accountEventRepo.save(AccountEvent.simpleNonBillingEvent(
      eventTime = clock.now,
      accountId = orgId2AccountId(orgId),
      attribution = attribution,
      action = AccountEventAction.AdminRemoved(userId)
    ))
  }

  def removeUserAccountContact(orgId: Id[Organization], userId: Id[User], attribution: ActionAttribution): AccountEvent = db.readWrite { implicit session =>
    val account = paidAccountRepo.getByOrgId(orgId)
    val updatedAccount = account.copy(userContacts = account.userContacts.filter(_ != userId))
    paidAccountRepo.save(updatedAccount)
    accountEventRepo.save(AccountEvent.simpleNonBillingEvent(
      eventTime = clock.now,
      accountId = updatedAccount.id.get,
      attribution = attribution,
      action = AccountEventAction.AccountContactsChanged(userAdded = None, userRemoved = Some(userId), emailAdded = None, emailRemoved = None)
    ))
  }

  def removeEmailAccountContact(orgId: Id[Organization], emailAddress: EmailAddress, attribution: ActionAttribution): AccountEvent = db.readWrite { implicit session =>
    val account = paidAccountRepo.getByOrgId(orgId)
    val updatedAccount = account.copy(emailContacts = account.emailContacts.filter(_ != emailAddress))
    paidAccountRepo.save(updatedAccount)
    accountEventRepo.save(AccountEvent.simpleNonBillingEvent(
      eventTime = clock.now,
      accountId = updatedAccount.id.get,
      attribution = attribution,
      action = AccountEventAction.AccountContactsChanged(userAdded = None, userRemoved = None, emailAdded = None, emailRemoved = Some(emailAddress))
    ))
  }

  def addUserAccountContact(orgId: Id[Organization], userId: Id[User], attribution: ActionAttribution): AccountEvent = db.readWrite { implicit session =>
    val account = paidAccountRepo.getByOrgId(orgId)
    val updatedAccount = account.copy(userContacts = account.userContacts.filter(_ != userId) :+ userId)
    paidAccountRepo.save(updatedAccount)
    accountEventRepo.save(AccountEvent.simpleNonBillingEvent(
      eventTime = clock.now,
      accountId = updatedAccount.id.get,
      attribution = attribution,
      action = AccountEventAction.AccountContactsChanged(userAdded = Some(userId), userRemoved = None, emailAdded = None, emailRemoved = None)
    ))
  }

  def addEmailAccountContact(orgId: Id[Organization], emailAddress: EmailAddress, attribution: ActionAttribution): AccountEvent = db.readWrite { implicit session =>
    val account = paidAccountRepo.getByOrgId(orgId)
    val updatedAccount = account.copy(emailContacts = account.emailContacts.filter(_ != emailAddress) :+ emailAddress)
    paidAccountRepo.save(updatedAccount)
    accountEventRepo.save(AccountEvent.simpleNonBillingEvent(
      eventTime = clock.now,
      accountId = updatedAccount.id.get,
      attribution = attribution,
      action = AccountEventAction.AccountContactsChanged(userAdded = None, userRemoved = None, emailAdded = Some(emailAddress), emailRemoved = None)
    ))
  }

  def getAccountContacts(orgId: Id[Organization]): (Seq[Id[User]], Seq[EmailAddress]) = db.readOnlyMaster { implicit session =>
    val account = paidAccountRepo.getByOrgId(orgId)
    (account.userContacts, account.emailContacts)
  }

  def grantSpecialCredit(orgId: Id[Organization], amount: DollarAmount, grantedByAdmin: Option[Id[User]], memo: Option[String]): AccountEvent = db.readWrite { implicit session =>
    val account = paidAccountRepo.getByOrgId(orgId)
    paidAccountRepo.save(account.copy(credit = account.credit + amount))
    accountEventRepo.save(AccountEvent.simpleNonBillingEvent(
      eventTime = clock.now,
      accountId = account.id.get,
      attribution = ActionAttribution(user = None, admin = grantedByAdmin),
      action = AccountEventAction.SpecialCredit()
    ))
  }

  def getCurrentCredit(orgId: Id[Organization]): DollarAmount = db.readOnlyMaster { implicit session =>
    paidAccountRepo.getByOrgId(orgId).credit
  }

  def createNewPlan(name: Name[PaidPlan], billingCycle: BillingCycle, price: DollarAmount, custom: Boolean = false): PaidPlan = db.readWrite { implicit session =>
    paidPlanRepo.save(PaidPlan(
      kind = if (custom) PaidPlan.Kind.CUSTOM else PaidPlan.Kind.NORMAL,
      name = name,
      billingCycle = billingCycle,
      pricePerCyclePerUser = price
    ))
  }

  def grandfatherPlan(id: Id[PaidPlan]): Try[PaidPlan] = db.readWrite { implicit session =>
    val plan = paidPlanRepo.get(id)
    if (plan.state == PaidPlanStates.ACTIVE && plan.kind == PaidPlan.Kind.NORMAL) {
      Success(paidPlanRepo.save(plan.copy(kind = PaidPlan.Kind.GRANDFATHERED)))
    } else {
      Failure(new InvalidChange("plan_not_active"))
    }
  }

  def deactivatePlan(id: Id[PaidPlan]): Try[PaidPlan] = db.readWrite { implicit session =>
    val plan = paidPlanRepo.get(id)
    val accounts = paidAccountRepo.getActiveByPlan(id)
    if (accounts.isEmpty) {
      Success(paidPlanRepo.save(plan.withState(PaidPlanStates.INACTIVE)))
    } else {
      Failure(new InvalidChange("plan_in_use"))
    }
  }

  def getAvailablePlans(grantedByAdmin: Option[Id[User]] = None): Seq[PaidPlan] = db.readOnlyMaster { implicit session =>
    val kinds = if (grantedByAdmin.isDefined) Set(PaidPlan.Kind.NORMAL, PaidPlan.Kind.CUSTOM) else Set(PaidPlan.Kind.NORMAL)
    paidPlanRepo.getByKinds(kinds)
  }

  def changePlan(orgId: Id[Organization], newPlanId: Id[PaidPlan], attribution: ActionAttribution): Try[AccountEvent] = db.readWrite { implicit session =>
    val account = paidAccountRepo.getByOrgId(orgId)
    val newPlan = paidPlanRepo.get(newPlanId)
    val allowedKinds = Set(PaidPlan.Kind.NORMAL) ++ attribution.admin.map(_ => PaidPlan.Kind.CUSTOM)
    if (newPlan.state == PaidPlanStates.ACTIVE && allowedKinds.contains(newPlan.kind)) {
      paidAccountRepo.save(account.copy(planId = newPlanId))
      Success(accountEventRepo.save(AccountEvent.simpleNonBillingEvent(
        eventTime = clock.now,
        accountId = account.id.get,
        attribution = attribution,
        action = AccountEventAction.PlanChanged(account.planId, newPlanId),
        pending = true
      )))
    } else {
      Failure(new InvalidChange("plan_not_available"))
    }
  }

  def getActivePaymentMethods(orgId: Id[Organization]): Seq[PaymentMethod] = db.readOnlyMaster { implicit session =>
    paymentMethodRepo.getByAccountId(orgId2AccountId(orgId))
  }

  def addPaymentMethod(orgId: Id[Organization], stripeToken: StripeToken, attribution: ActionAttribution): AccountEvent = db.readWrite { implicit session =>
    val accountId = orgId2AccountId(orgId)
    val newPaymentMethod = paymentMethodRepo.save(PaymentMethod(
      accountId = accountId,
      default = false,
      stripeToken = stripeToken
    ))
    accountEventRepo.save(AccountEvent.simpleNonBillingEvent(
      eventTime = clock.now,
      accountId = accountId,
      attribution = attribution,
      action = AccountEventAction.PaymentMethodAdded(newPaymentMethod.id.get)
    ))
  }

  def changeDefaultPaymentMethod(orgId: Id[Organization], newDefaultId: Id[PaymentMethod], attribution: ActionAttribution): Try[AccountEvent] = db.readWrite { implicit session =>
    val accountId = orgId2AccountId(orgId)
    val newDefault = paymentMethodRepo.get(newDefaultId)
    val oldDefaultOpt = paymentMethodRepo.getDefault(accountId)
    if (newDefault.state != PaymentMethodStates.ACTIVE) {
      Failure(new InvalidChange("payment_method_not_available"))
    } else {
      oldDefaultOpt.map { oldDefault =>
        paymentMethodRepo.save(oldDefault.copy(default = false))
      }
      paymentMethodRepo.save(newDefault.copy(default = true))
      Success(accountEventRepo.save(AccountEvent.simpleNonBillingEvent(
        eventTime = clock.now,
        accountId = accountId,
        attribution = attribution,
        action = AccountEventAction.DefaultPaymentMethodChanged(oldDefaultOpt.map(_.id.get), newDefault.id.get)
      )))
    }

  }

  def getAccountEvents(orgId: Id[Organization], beforeOpt: Option[DateTime], limit: Int, onlyRelatedToBilling: Option[Boolean]): Seq[AccountEvent] = db.readOnlyMaster { implicit session =>
    val accountId = orgId2AccountId(orgId)
    beforeOpt.map { before =>
      accountEventRepo.getEventsBefore(accountId, before, limit, onlyRelatedToBilling)
    } getOrElse {
      accountEventRepo.getEvents(accountId, limit, onlyRelatedToBilling)
    }
  }

}

