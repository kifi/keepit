package com.keepit.payments

import com.keepit.commanders.{ PermissionCommander, OrganizationCommander }
import com.keepit.common.logging.Logging
import com.keepit.common.db.slick.Database
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.{ Id, ExternalId }
import com.keepit.common.time._
import com.keepit.model._
import com.keepit.common.mail.EmailAddress
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.social.BasicUserRepo

import scala.concurrent.ExecutionContext
import scala.util.{ Try, Success, Failure }

import play.api.libs.json.{ JsObject, JsValue, Writes, JsNull }
import java.math.{ BigDecimal, MathContext, RoundingMode }

import play.api.libs.json.JsNull

import com.google.inject.{ ImplementedBy, Inject }

import org.joda.time.{ DateTime, Days }

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
  def createNewPlan(name: Name[PaidPlan], billingCycle: BillingCycle, price: DollarAmount, custom: Boolean = false, editableFeatures: Set[Feature], defaultSettings: OrganizationSettings): PaidPlan

  def grandfatherPlan(id: Id[PaidPlan]): Try[PaidPlan]
  def deactivatePlan(id: Id[PaidPlan]): Try[PaidPlan]

  def getAvailablePlans(grantedByAdmin: Option[Id[User]] = None): Seq[PaidPlan]
  def changePlan(orgId: Id[Organization], newPlan: Id[PaidPlan], attribution: ActionAttribution): Try[AccountEvent]
  def getBillingCycleStart(orgId: Id[Organization]): DateTime

  def getActivePaymentMethods(orgId: Id[Organization]): Seq[PaymentMethod]
  def addPaymentMethod(orgId: Id[Organization], stripeToken: StripeToken, attribution: ActionAttribution): PaymentMethod
  def changeDefaultPaymentMethod(orgId: Id[Organization], newDefault: Id[PaymentMethod], attribution: ActionAttribution): Try[AccountEvent]
  def getDefaultPaymentMethod(orgId: Id[Organization]): Option[PaymentMethod]

  def getAccountEvents(orgId: Id[Organization], max: Int, onlyRelatedToBillingFilter: Option[Boolean]): Seq[AccountEvent]
  def getAccountEventsBefore(orgId: Id[Organization], beforeTime: DateTime, beforeId: Id[AccountEvent], max: Int, onlyRelatedToBillingFilter: Option[Boolean]): Seq[AccountEvent]

  def getAccountFeatureSettings(orgId: Id[Organization]): OrganizationSettingsResponse
  def setAccountFeatureSettings(orgId: Id[Organization], userId: Id[User], settings: OrganizationSettings): Try[OrganizationSettingsResponse]
  def setAccountFeatureSettingsHelper(orgId: Id[Organization], userId: Id[User], settings: OrganizationSettings)(implicit session: RWSession): Try[OrganizationSettingsResponse]

  private[payments] def registerRemovedUserHelper(orgId: Id[Organization], userId: Id[User], attribution: ActionAttribution)(implicit session: RWSession): AccountEvent
  private[payments] def registerNewUserHelper(orgId: Id[Organization], userId: Id[User], attribution: ActionAttribution)(implicit session: RWSession): AccountEvent

  //UTILITIES
  def buildSimpleEventInfo(event: AccountEvent): SimpleAccountEventInfo
}

class PlanManagementCommanderImpl @Inject() (
  db: Database,
  paymentMethodRepo: PaymentMethodRepo,
  accountEventRepo: AccountEventRepo,
  paidAccountRepo: PaidAccountRepo,
  paidPlanRepo: PaidPlanRepo,
  orgRepo: OrganizationRepo,
  orgMembershipRepo: OrganizationMembershipRepo,
  orgConfigRepo: OrganizationConfigurationRepo,
  basicUserRepo: BasicUserRepo,
  clock: Clock,
  airbrake: AirbrakeNotifier,
  userRepo: UserRepo,
  accountLockHelper: AccountLockHelper,
  permissionCommander: PermissionCommander,
  implicit val defaultContext: ExecutionContext,
  implicit val publicIdConfig: PublicIdConfiguration)
    extends PlanManagementCommander with Logging {

  private val MATH_CONTEXT = new MathContext(34, RoundingMode.HALF_DOWN)

  private def orgId2AccountId(orgId: Id[Organization])(implicit session: RSession): Id[PaidAccount] = {
    paidAccountRepo.getAccountId(orgId)
  }

  //very explicitly accepts a db session to allow account creation on org creation within the same db session
  private def remainingBillingCycleCost(account: PaidAccount)(implicit session: RSession): DollarAmount = {
    val plan = paidPlanRepo.get(account.planId)
    val cycleLengthMonth: Int = plan.billingCycle.month
    val cycleStart: DateTime = account.billingCycleStart
    val cycleEnd: DateTime = cycleStart.plusMonths(cycleLengthMonth)
    val cycleLengthDays: Double = Days.daysBetween(cycleStart, cycleEnd).getDays.toDouble //note that this is different depending on the current month
    val remaining: Double = Days.daysBetween(clock.now, cycleEnd).getDays.toDouble
    val fraction: Double = remaining / cycleLengthDays
    val fullPrice = new BigDecimal(plan.pricePerCyclePerUser.cents, MATH_CONTEXT)
    val remainingPrice = fullPrice.multiply(new BigDecimal(fraction, MATH_CONTEXT), MATH_CONTEXT).setScale(0, RoundingMode.HALF_DOWN)
    DollarAmount(remainingPrice.intValueExact)

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
              val account = paidAccountRepo.save(PaidAccount(
                id = pa.id,
                orgId = orgId,
                planId = planId,
                credit = DollarAmount(0),
                userContacts = Seq.empty,
                emailContacts = Seq.empty,
                activeUsers = 0,
                billingCycleStart = clock.now
              ))
              if (accountLockHelper.acquireAccountLockForSession(orgId, session)) {
                Success(account)
              } else {
                Failure(new Exception("failed_getting_account_lock")) //super safeguard, this should not be possible at this stage
              }
            case None =>
              log.info(s"[PAC] $orgId: Creating Account")
              val account = paidAccountRepo.save(PaidAccount(
                orgId = orgId,
                planId = planId,
                credit = DollarAmount(0),
                userContacts = Seq.empty,
                emailContacts = Seq.empty,
                activeUsers = 0,
                billingCycleStart = clock.now
              ))
              if (accountLockHelper.acquireAccountLockForSession(orgId, session)) {
                Success(account)
              } else {
                Failure(new Exception("failed_getting_account_lock")) //super safeguard, this should not be possible at this stage
              }
          }
          maybeAccount.map { account =>
            log.info(s"[PAC] $orgId: Registering First User")
            registerNewUserHelper(orgId, creator, ActionAttribution(user = Some(creator), admin = None))
            log.info(s"[PAC] $orgId: Registering First Admin")
            val adminEvent = accountEventRepo.save(AccountEvent.simpleNonBillingEvent(
              eventTime = clock.now,
              accountId = account.id.get,
              attribution = ActionAttribution(user = Some(creator), admin = None),
              action = AccountEventAction.AdminAdded(creator)
            ))
            accountLockHelper.releaseAccountLockForSession(orgId, session)
            adminEvent
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

  private[payments] def registerNewUserHelper(orgId: Id[Organization], userId: Id[User], attribution: ActionAttribution)(implicit session: RWSession) = {
    val account = paidAccountRepo.getByOrgId(orgId)
    val price: DollarAmount = remainingBillingCycleCost(account)
    paidAccountRepo.save(
      account.withReducedCredit(price).withMoreActiveUsers(1)
    )
    accountEventRepo.save(AccountEvent.simpleNonBillingEvent(
      eventTime = clock.now,
      accountId = account.id.get,
      attribution = attribution,
      action = AccountEventAction.UserAdded(userId),
      creditChange = DollarAmount(-1 * price.cents)
    ))
  }

  def registerNewUser(orgId: Id[Organization], userId: Id[User], attribution: ActionAttribution): AccountEvent = accountLockHelper.maybeSessionWithAccountLock(orgId, attempts = 3) { implicit session =>
    registerNewUserHelper(orgId, userId, attribution)
  }.get //if this fails we have failed to get the account lock despite repeated tries, indicating something serious is wrong, and we are going to bail hard

  private[payments] def registerRemovedUserHelper(orgId: Id[Organization], userId: Id[User], attribution: ActionAttribution)(implicit session: RWSession): AccountEvent = {
    val account = paidAccountRepo.getByOrgId(orgId)
    val price: DollarAmount = remainingBillingCycleCost(account)
    paidAccountRepo.save(
      account.withIncreasedCredit(price).withFewerActiveUsers(1)
    )
    accountEventRepo.save(AccountEvent.simpleNonBillingEvent(
      eventTime = clock.now,
      accountId = orgId2AccountId(orgId),
      attribution = attribution,
      action = AccountEventAction.UserRemoved(userId),
      creditChange = price
    ))
  }

  def registerRemovedUser(orgId: Id[Organization], userId: Id[User], attribution: ActionAttribution): AccountEvent = accountLockHelper.maybeSessionWithAccountLock(orgId, attempts = 3) { implicit session =>
    registerRemovedUserHelper(orgId, userId, attribution)
  }.get //if this fails we have failed to get the account lock despite repeated tries, indicating something serious is wrong, and we are going to bail hard

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

  def grantSpecialCredit(orgId: Id[Organization], amount: DollarAmount, grantedByAdmin: Option[Id[User]], memo: Option[String]): AccountEvent = accountLockHelper.maybeSessionWithAccountLock(orgId, attempts = 3) { implicit session =>
    val account = paidAccountRepo.getByOrgId(orgId)
    paidAccountRepo.save(account.withIncreasedCredit(amount))
    accountEventRepo.save(AccountEvent(
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
      memo = memo,
      chargeId = None
    ))
  }.get

  def getCurrentCredit(orgId: Id[Organization]): DollarAmount = db.readOnlyMaster { implicit session =>
    paidAccountRepo.getByOrgId(orgId).credit
  }

  def currentPlan(orgId: Id[Organization]): PaidPlan = db.readOnlyMaster { implicit session =>
    val account = paidAccountRepo.getByOrgId(orgId)
    paidPlanRepo.get(account.planId)
  }

  def createNewPlan(name: Name[PaidPlan], billingCycle: BillingCycle, price: DollarAmount, custom: Boolean = false, editableFeatures: Set[Feature], defaultSettings: OrganizationSettings): PaidPlan = {
    db.readWrite { implicit session => createNewPlanHelper(name, billingCycle, price, custom, editableFeatures, defaultSettings) }
  }

  def createNewPlanHelper(name: Name[PaidPlan], billingCycle: BillingCycle, price: DollarAmount, custom: Boolean = false, editableFeatures: Set[Feature], defaultSettings: OrganizationSettings)(implicit session: RWSession): PaidPlan = {
    paidPlanRepo.save(PaidPlan(kind = if (custom) PaidPlan.Kind.CUSTOM else PaidPlan.Kind.NORMAL,
      name = name,
      billingCycle = billingCycle,
      pricePerCyclePerUser = price,
      editableFeatures = editableFeatures,
      defaultSettings = defaultSettings
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

  def changePlan(orgId: Id[Organization], newPlanId: Id[PaidPlan], attribution: ActionAttribution): Try[AccountEvent] = accountLockHelper.maybeSessionWithAccountLock(orgId, attempts = 2) { implicit session =>
    val account = paidAccountRepo.getByOrgId(orgId)
    val newPlan = paidPlanRepo.get(newPlanId)
    val allowedKinds = Set(PaidPlan.Kind.NORMAL) ++ attribution.admin.map(_ => PaidPlan.Kind.CUSTOM)
    if (newPlan.state == PaidPlanStates.ACTIVE && allowedKinds.contains(newPlan.kind)) {
      val updatedAccount = account.withNewPlan(newPlanId)
      val refund = DollarAmount(remainingBillingCycleCost(account).cents * account.activeUsers)
      val newCharge = DollarAmount(remainingBillingCycleCost(updatedAccount).cents * account.activeUsers)
      paidAccountRepo.save(
        updatedAccount.withIncreasedCredit(refund).withReducedCredit(newCharge)
      )
      Success(accountEventRepo.save(AccountEvent.simpleNonBillingEvent(
        eventTime = clock.now,
        accountId = account.id.get,
        attribution = attribution,
        action = AccountEventAction.PlanChanged(account.planId, newPlanId),
        creditChange = DollarAmount(refund.cents - newCharge.cents)
      )))
    } else {
      Failure(new InvalidChange("plan_not_available"))
    }
  }.getOrElse {
    Failure(new Exception("failed_getting_account_lock"))
  }

  def getBillingCycleStart(orgId: Id[Organization]): DateTime = db.readOnlyMaster { implicit session =>
    paidAccountRepo.getByOrgId(orgId).billingCycleStart
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

  def getDefaultPaymentMethod(orgId: Id[Organization]): Option[PaymentMethod] = {
    getActivePaymentMethods(orgId).find(_.default)
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

  def getAccountFeatureSettings(orgId: Id[Organization]): OrganizationSettingsResponse = {
    db.readOnlyReplica { implicit session =>
      val config = orgConfigRepo.getByOrgId(orgId)
      OrganizationSettingsResponse(config)
    }
  }

  def setAccountFeatureSettings(orgId: Id[Organization], userId: Id[User], settings: OrganizationSettings): Try[OrganizationSettingsResponse] = {
    db.readWrite { implicit session => setAccountFeatureSettingsHelper(orgId, userId, settings) }
  }

  def setAccountFeatureSettingsHelper(orgId: Id[Organization], userId: Id[User], settings: OrganizationSettings)(implicit session: RWSession): Try[OrganizationSettingsResponse] = {
    if (!permissionCommander.getOrganizationPermissions(orgId, Some(userId)).contains(OrganizationPermission.MANAGE_PLAN)) Failure(OrganizationFail.INSUFFICIENT_PERMISSIONS)
    else {
      val currentConfig = orgConfigRepo.getByOrgId(orgId)
      val newConfig = orgConfigRepo.save(currentConfig.withSettings(settings))
      Success(OrganizationSettingsResponse(newConfig))
    }
  }
}

