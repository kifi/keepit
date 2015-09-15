package com.keepit.payments

import com.keepit.common.logging.Logging
import com.keepit.common.db.slick.Database
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.{ Id, ExternalId }
import com.keepit.common.time._
import com.keepit.model.{ Organization, User, Name, OrganizationMembershipRepo, OrganizationRole, UserRepo }
import com.keepit.common.mail.EmailAddress
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.social.BasicUserRepo

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

  def removeUserAccountContact(orgId: Id[Organization], userId: Id[User], attribution: ActionAttribution): Option[AccountEvent]
  def removeEmailAccountContact(orgId: Id[Organization], emailAddress: EmailAddress, attribution: ActionAttribution): Option[AccountEvent]
  def addUserAccountContact(orgId: Id[Organization], userId: Id[User], attribution: ActionAttribution): Option[AccountEvent]
  def addEmailAccountContact(orgId: Id[Organization], emailAddress: EmailAddress, attribution: ActionAttribution): Option[AccountEvent]
  def getAccountContacts(orgId: Id[Organization]): (Seq[Id[User]], Seq[EmailAddress])
  def getSimpleContactInfos(orgId: Id[Organization]): Seq[SimpleAccountContactInfo]
  def updateUserContact(orgId: Id[Organization], extId: ExternalId[User], enabled: Boolean, attribution: ActionAttribution): Option[AccountEvent]

  def grantSpecialCredit(orgId: Id[Organization], amount: DollarAmount, grantedByAdmin: Option[Id[User]], memo: Option[String]): AccountEvent
  def getCurrentCredit(orgId: Id[Organization]): DollarAmount

  def currentPlan(orgId: Id[Organization]): PaidPlan
  def createNewPlan(name: Name[PaidPlan], billingCycle: BillingCycle, price: DollarAmount, custom: Boolean = false): PaidPlan

  def grandfatherPlan(id: Id[PaidPlan]): Try[PaidPlan]
  def deactivatePlan(id: Id[PaidPlan]): Try[PaidPlan]

  def getAvailablePlans(grantedByAdmin: Option[Id[User]] = None): Seq[PaidPlan]
  def changePlan(orgId: Id[Organization], newPlan: Id[PaidPlan], attribution: ActionAttribution): Try[AccountEvent]

  def getActivePaymentMethods(orgId: Id[Organization]): Seq[PaymentMethod]
  def addPaymentMethod(orgId: Id[Organization], stripeToken: StripeToken, attribution: ActionAttribution): PaymentMethod
  def changeDefaultPaymentMethod(orgId: Id[Organization], newDefault: Id[PaymentMethod], attribution: ActionAttribution): Try[AccountEvent]

  def getAccountEvents(orgId: Id[Organization], max: Int, onlyRelatedToBillingFilter: Option[Boolean]): Seq[AccountEvent]
  def getAccountEventsBefore(orgId: Id[Organization], beforeTime: DateTime, beforeId: Id[AccountEvent], max: Int, onlyRelatedToBillingFilter: Option[Boolean]): Seq[AccountEvent]

  //UTILITIES
  def buildSimpleEventInfo(event: AccountEvent): SimpleAccountEventInfo
}

class PlanManagementCommanderImpl @Inject() (
  db: Database,
  paymentMethodRepo: PaymentMethodRepo,
  accountEventRepo: AccountEventRepo,
  paidAccountRepo: PaidAccountRepo,
  paidPlanRepo: PaidPlanRepo,
  orgMembershipRepo: OrganizationMembershipRepo,
  basicUserRepo: BasicUserRepo,
  clock: Clock,
  airbrake: AirbrakeNotifier,
  userRepo: UserRepo,
  implicit val defaultContext: ExecutionContext,
  implicit val publicIdConfig: PublicIdConfiguration)
    extends PlanManagementCommander with Logging {

  private def orgId2AccountId(orgId: Id[Organization])(implicit session: RSession): Id[PaidAccount] = {
    paidAccountRepo.getAccountId(orgId)
  }

  //very explicitely accepts a db session to allow account creation on org creation within the same db session
  def createAndInitializePaidAccountForOrganization(orgId: Id[Organization], planId: Id[PaidPlan], creator: Id[User], session: RWSession): Try[AccountEvent] = {
    implicit val s = session
    Try { paidPlanRepo.get(planId) } match {
      case Success(plan) => {
        log.info(s"[PAC] $orgId: Plan exists.")
        if (plan.state != PaidPlanStates.ACTIVE) {
          Failure(new InvalidChange("plan_not_active"))
        } else {
          val maybeAccount = paidAccountRepo.maybeGetByOrgId(orgId, Set()) match {
            case Some(pa) if pa.state == PaidAccountStates.ACTIVE => {
              log.info(s"[PAC] $orgId: Account already exists.")
              Failure(new InvalidChange("account_exists"))
            }
            case Some(pa) =>
              log.info(s"[PAC] $orgId: Recreating Account")
              Success(paidAccountRepo.save(PaidAccount(
                id = pa.id,
                orgId = orgId,
                planId = planId,
                credit = DollarAmount(0),
                userContacts = Seq.empty,
                emailContacts = Seq.empty,
                activeUsers = 0
              )))
            case None =>
              log.info(s"[PAC] $orgId: Creating Account")
              Success(paidAccountRepo.save(PaidAccount(
                orgId = orgId,
                planId = planId,
                credit = DollarAmount(0),
                userContacts = Seq.empty,
                emailContacts = Seq.empty,
                activeUsers = 0
              )))
          }
          maybeAccount.map { account =>
            log.info(s"[PAC] $orgId: Registering First User")
            accountEventRepo.save(AccountEvent.simpleNonBillingEvent(
              eventTime = clock.now,
              accountId = account.id.get,
              attribution = ActionAttribution(user = Some(creator), admin = None),
              action = AccountEventAction.UserAdded(creator),
              pending = true
            ))
            log.info(s"[PAC] $orgId: Registering First Admin")
            accountEventRepo.save(AccountEvent.simpleNonBillingEvent(
              eventTime = clock.now,
              accountId = account.id.get,
              attribution = ActionAttribution(user = Some(creator), admin = None),
              action = AccountEventAction.AdminAdded(creator)
            ))
          }
        }
      }
      case Failure(ex) => {
        log.error(s"[PAC] $orgId: Plan does not exist!", ex)
        airbrake.notify("Paid Plan Not available!!", ex)
        Failure(new InvalidChange("plan_not_available"))
      }
    }

  }

  def deactivatePaidAccountForOrganziation(orgId: Id[Organization], session: RWSession): Try[Unit] = {
    implicit val s = session
    Try {
      paidAccountRepo.maybeGetByOrgId(orgId).foreach { account =>
        paidAccountRepo.save(account.withState(PaidAccountStates.INACTIVE))
        accountEventRepo.deactivateAll(account.id.get)
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

  def removeUserAccountContact(orgId: Id[Organization], userId: Id[User], attribution: ActionAttribution): Option[AccountEvent] = db.readWrite { implicit session =>
    val account = paidAccountRepo.getByOrgId(orgId)
    if (account.userContacts.contains(userId)) {
      val updatedAccount = account.copy(userContacts = account.userContacts.filter(_ != userId))
      paidAccountRepo.save(updatedAccount)
      Some(accountEventRepo.save(AccountEvent.simpleNonBillingEvent(
        eventTime = clock.now,
        accountId = updatedAccount.id.get,
        attribution = attribution,
        action = AccountEventAction.AccountContactsChanged(userAdded = None, userRemoved = Some(userId), emailAdded = None, emailRemoved = None)
      )))
    } else None
  }

  def removeEmailAccountContact(orgId: Id[Organization], emailAddress: EmailAddress, attribution: ActionAttribution): Option[AccountEvent] = db.readWrite { implicit session =>
    val account = paidAccountRepo.getByOrgId(orgId)
    if (account.emailContacts.contains(emailAddress)) {
      val updatedAccount = account.copy(emailContacts = account.emailContacts.filter(_ != emailAddress))
      paidAccountRepo.save(updatedAccount)
      Some(accountEventRepo.save(AccountEvent.simpleNonBillingEvent(
        eventTime = clock.now,
        accountId = updatedAccount.id.get,
        attribution = attribution,
        action = AccountEventAction.AccountContactsChanged(userAdded = None, userRemoved = None, emailAdded = None, emailRemoved = Some(emailAddress))
      )))
    } else None
  }

  def addUserAccountContact(orgId: Id[Organization], userId: Id[User], attribution: ActionAttribution): Option[AccountEvent] = db.readWrite { implicit session =>
    val account = paidAccountRepo.getByOrgId(orgId)
    if (!account.userContacts.contains(userId)) {
      val updatedAccount = account.copy(userContacts = account.userContacts.filter(_ != userId) :+ userId)
      paidAccountRepo.save(updatedAccount)
      Some(accountEventRepo.save(AccountEvent.simpleNonBillingEvent(
        eventTime = clock.now,
        accountId = updatedAccount.id.get,
        attribution = attribution,
        action = AccountEventAction.AccountContactsChanged(userAdded = Some(userId), userRemoved = None, emailAdded = None, emailRemoved = None)
      )))
    } else None
  }

  def addEmailAccountContact(orgId: Id[Organization], emailAddress: EmailAddress, attribution: ActionAttribution): Option[AccountEvent] = db.readWrite { implicit session =>
    val account = paidAccountRepo.getByOrgId(orgId)
    if (!account.emailContacts.contains(emailAddress)) {
      val updatedAccount = account.copy(emailContacts = account.emailContacts.filter(_ != emailAddress) :+ emailAddress)
      paidAccountRepo.save(updatedAccount)
      Some(accountEventRepo.save(AccountEvent.simpleNonBillingEvent(
        eventTime = clock.now,
        accountId = updatedAccount.id.get,
        attribution = attribution,
        action = AccountEventAction.AccountContactsChanged(userAdded = None, userRemoved = None, emailAdded = Some(emailAddress), emailRemoved = None)
      )))
    } else None
  }

  def getAccountContacts(orgId: Id[Organization]): (Seq[Id[User]], Seq[EmailAddress]) = db.readOnlyMaster { implicit session =>
    val account = paidAccountRepo.getByOrgId(orgId)
    (account.userContacts, account.emailContacts)
  }

  def getSimpleContactInfos(orgId: Id[Organization]): Seq[SimpleAccountContactInfo] = db.readOnlyMaster { implicit session =>
    val account = paidAccountRepo.getByOrgId(orgId)
    val registered: Map[Id[User], Boolean] = account.userContacts.toSet.map { userId: Id[User] => (userId, true) }.toMap
    val potential: Map[Id[User], Boolean] = orgMembershipRepo.getByRole(orgId, OrganizationRole.ADMIN).toSet.map { userId: Id[User] => (userId, false) }.toMap
    val all = potential ++ registered
    val bus = basicUserRepo.loadAll(all.keySet)
    all.map {
      case (userId, enabled) =>
        SimpleAccountContactInfo(bus(userId), enabled)
    }.toSeq
  }

  def updateUserContact(orgId: Id[Organization], extId: ExternalId[User], enabled: Boolean, attribution: ActionAttribution): Option[AccountEvent] = {
    val user = db.readOnlyMaster { implicit session => userRepo.getByExternalId(extId) }
    if (enabled) {
      addUserAccountContact(orgId, user.id.get, attribution)
    } else {
      removeUserAccountContact(orgId, user.id.get, attribution)
    }
  }

  def grantSpecialCredit(orgId: Id[Organization], amount: DollarAmount, grantedByAdmin: Option[Id[User]], memo: Option[String]): AccountEvent = db.readWrite { implicit session =>
    val account = paidAccountRepo.getByOrgId(orgId)
    paidAccountRepo.save(account.copy(credit = account.credit + amount))
    accountEventRepo.save(AccountEvent(
      stage = AccountEvent.ProcessingStage.COMPLETE,
      eventGroup = EventGroup(),
      eventTime = clock.now(),
      accountId = account.id.get,
      billingRelated = false,
      whoDunnit = None,
      whoDunnitExtra = JsNull,
      kifiAdminInvolved = grantedByAdmin,
      action = AccountEventAction.SpecialCredit(),
      creditChange = amount,
      paymentMethod = None,
      paymentCharge = None,
      memo = memo
    ))
  }

  def getCurrentCredit(orgId: Id[Organization]): DollarAmount = db.readOnlyMaster { implicit session =>
    paidAccountRepo.getByOrgId(orgId).credit
  }

  def currentPlan(orgId: Id[Organization]): PaidPlan = db.readOnlyMaster { implicit session =>
    val account = paidAccountRepo.getByOrgId(orgId)
    paidPlanRepo.get(account.planId)
  }

  def createNewPlan(name: Name[PaidPlan], billingCycle: BillingCycle, price: DollarAmount, custom: Boolean = false): PaidPlan = {
    db.readWrite { implicit session => createNewPlanHelper(name, billingCycle, price, custom) }
  }

  def createNewPlanHelper(name: Name[PaidPlan], billingCycle: BillingCycle, price: DollarAmount, custom: Boolean = false)(implicit session: RWSession): PaidPlan = {
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

  def addPaymentMethod(orgId: Id[Organization], stripeToken: StripeToken, attribution: ActionAttribution): PaymentMethod = db.readWrite { implicit session =>
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
    newPaymentMethod
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

  def getAccountEvents(orgId: Id[Organization], limit: Int, onlyRelatedToBilling: Option[Boolean]): Seq[AccountEvent] = db.readOnlyMaster { implicit session =>
    val accountId = orgId2AccountId(orgId)
    accountEventRepo.getEvents(accountId, limit, onlyRelatedToBilling)
  }

  def getAccountEventsBefore(orgId: Id[Organization], beforeTime: DateTime, beforeId: Id[AccountEvent], limit: Int, onlyRelatedToBilling: Option[Boolean]): Seq[AccountEvent] = db.readOnlyMaster { implicit session =>
    val accountId = orgId2AccountId(orgId)
    accountEventRepo.getEventsBefore(accountId, beforeTime, beforeId, limit, onlyRelatedToBilling)
  }

  def buildSimpleEventInfo(event: AccountEvent): SimpleAccountEventInfo = db.readOnlyMaster { implicit session =>
    import AccountEventAction._
    val maybeUser = event.whoDunnit.map(basicUserRepo.load)
    val maybeAdmin = event.kifiAdminInvolved.map(basicUserRepo.load)
    val whoDunnit = (maybeUser, maybeAdmin) match {
      case (Some(user), Some(admin)) => s"${user.firstName} ${user.lastName} with Kifi Admin {admin.firstName} ${admin.lastName}"
      case (Some(user), None) => s"${user.firstName} ${user.lastName}"
      case (None, Some(admin)) => s"Kifi Admin {admin.firstName} ${admin.lastName}"
      case (None, None) => s"System"
    }
    val shortName = event.action match {
      case SpecialCredit() => "Special Credit Given"
      case ChargeBack() => "Charge back to your Card"
      case PlanBillingCredit() => "Regular cost of your Plan deducted entirely from credit"
      case PlanBillingCharge() => "Regular cost of your Plan charged entirely to your card on file"
      case PlanBillingCreditPartial() => "Regular cost of your Plan deducted partially from remaining credit"
      case PlanBillingChargePartial() => "Regular cost of your Plan charged partially to your card on file (after credit was used up)"
      case PlanChangeCredit() => "Cost of previous plan credit back to your account"
      case UserChangeCredit() => "Credit for reduction in number of Users"
      case UserAdded(who) => {
        val user = basicUserRepo.load(who)
        s"${user.firstName} ${user.lastName} added to your organization"
      }
      case UserRemoved(who) => {
        val user = basicUserRepo.load(who)
        s"${user.firstName} ${user.lastName} removed from your organization"
      }
      case AdminAdded(who) => {
        val user = basicUserRepo.load(who)
        s"${user.firstName} ${user.lastName} made an admin of your organization"
      }
      case AdminRemoved(who) => {
        val user = basicUserRepo.load(who)
        s"${user.firstName} ${user.lastName} no longer an admin of your organization"
      }
      case PlanChanged(oldPlanId, newPlanId) => {
        val oldPlan = paidPlanRepo.get(oldPlanId)
        val newPlan = paidPlanRepo.get(newPlanId)
        s"Plan Changed from ${oldPlan.name} to ${newPlan.name}"
      }
      case PaymentMethodAdded(_) => "New Payment Method Added"
      case DefaultPaymentMethodChanged(_, _) => "Default Payment Method Changed"
      case AccountContactsChanged(userAdded: Option[Id[User]], userRemoved: Option[Id[User]], emailAdded: Option[EmailAddress], emailRemoved: Option[EmailAddress]) => {
        val userAddedOpt: Option[String] = userAdded.map { userId =>
          val bu = basicUserRepo.load(userId)
          s"${bu.firstName} ${bu.lastName}"
        }
        val userRemovedOpt: Option[String] = userRemoved.map { userId =>
          val bu = basicUserRepo.load(userId)
          s"${bu.firstName} ${bu.lastName}"
        }
        val emailAddedOpt: Option[String] = emailAdded.map(_.address)
        val emailRemovedOpt: Option[String] = emailRemoved.map(_.address)
        val addedSeq: Seq[String] = (userAddedOpt.toSeq ++ emailAddedOpt.toSeq)
        val removedSeq: Seq[String] = (userRemovedOpt.toSeq ++ emailRemovedOpt.toSeq)
        val added: String = if (addedSeq.isEmpty) "" else s" Added: ${addedSeq.mkString(", ")}."
        val removed: String = if (removedSeq.isEmpty) "" else s" Removed: ${removedSeq.mkString(", ")}."
        s"Account Contacts Changed.${added}${removed}"
      }
    }
    SimpleAccountEventInfo(
      id = AccountEvent.publicId(event.id.get),
      eventTime = event.eventTime,
      shortName = shortName,
      whoDunnit = whoDunnit,
      creditChange = event.creditChange.cents,
      paymentCharge = event.paymentCharge.map(_.cents).getOrElse(0),
      memo = event.memo
    )
  }

}

