package com.keepit.payments

import java.math.{ BigDecimal, MathContext, RoundingMode }

import com.google.inject.{ ImplementedBy, Inject }
import com.keepit.commanders.PermissionCommander
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import com.keepit.common.db.slick.Database
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.mail.EmailAddress
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.time._
import com.keepit.common.util.DollarAmount
import com.keepit.model._
import com.stripe.exception.APIException
import org.joda.time.{ DateTime, Days }
import play.api.libs.json._

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success, Try }

abstract class PlanManagementException(msg: String) extends Exception(msg)
case class UnauthorizedChange(msg: String) extends PlanManagementException(msg)
case class InvalidChange(msg: String) extends PlanManagementException(msg)

@ImplementedBy(classOf[PlanManagementCommanderImpl])
trait PlanManagementCommander {
  def createAndInitializePaidAccountForOrganization(orgId: Id[Organization], planId: Id[PaidPlan], creator: Id[User], session: RWSession): Try[AccountEvent]
  def deactivatePaidAccountForOrganization(orgId: Id[Organization], session: RWSession): Try[Unit]

  def registerNewUser(orgId: Id[Organization], userId: Id[User], role: OrganizationRole, attribution: ActionAttribution, overrideLock: Boolean = false)(implicit session: RWSession): AccountEvent
  def registerRemovedUser(orgId: Id[Organization], userId: Id[User], role: OrganizationRole, attribution: ActionAttribution, overrideLock: Boolean = false)(implicit session: RWSession): AccountEvent
  def registerRoleChanged(orgId: Id[Organization], userId: Id[User], from: OrganizationRole, to: OrganizationRole, attribution: ActionAttribution)(implicit session: RWSession): AccountEvent

  def removeUserAccountContact(orgId: Id[Organization], userId: Id[User], attribution: ActionAttribution): Option[AccountEvent]
  def removeEmailAccountContact(orgId: Id[Organization], emailAddress: EmailAddress, attribution: ActionAttribution): Option[AccountEvent]
  def addUserAccountContact(orgId: Id[Organization], userId: Id[User], attribution: ActionAttribution): Option[AccountEvent]
  def addUserAccountContactHelper(orgId: Id[Organization], userId: Id[User], attribution: ActionAttribution)(implicit session: RWSession): Option[AccountEvent]
  def addEmailAccountContact(orgId: Id[Organization], emailAddress: EmailAddress, attribution: ActionAttribution): Option[AccountEvent]
  def getAccountContacts(orgId: Id[Organization]): (Seq[Id[User]], Seq[EmailAddress])
  def getSimpleContactInfos(orgId: Id[Organization]): Seq[SimpleAccountContactInfo]
  def updateUserContact(orgId: Id[Organization], extId: ExternalId[User], enabled: Boolean, attribution: ActionAttribution): Option[AccountEvent]

  def grantSpecialCredit(orgId: Id[Organization], amount: DollarAmount, grantedByAdmin: Option[Id[User]], attributedToMember: Option[Id[User]], memo: Option[String]): AccountEvent
  def getCurrentCredit(orgId: Id[Organization]): DollarAmount

  def getAccountState(orgId: Id[Organization]): Future[AccountStateResponse]
  def previewAccountState(orgId: Id[Organization], newPlanId: Id[PaidPlan], newPaymentMethodId: Id[PaymentMethod]): Future[AccountStateResponse]

  def currentPlan(orgId: Id[Organization]): PaidPlan
  def currentPlanHelper(orgId: Id[Organization])(implicit session: RSession): PaidPlan

  def createNewPlan(name: Name[PaidPlan], displayName: String, billingCycle: BillingCycle, price: DollarAmount, custom: Boolean = false, editableFeatures: Set[Feature], defaultSettings: OrganizationSettings): PaidPlan

  def grandfatherPlan(id: Id[PaidPlan]): Try[PaidPlan]
  def deactivatePlan(id: Id[PaidPlan]): Try[PaidPlan]

  def getCurrentAndAvailablePlans(orgId: Id[Organization]): (Id[PaidPlan], Set[PaidPlan])
  def getAvailablePlans(grantedByAdmin: Option[Id[User]] = None): Seq[PaidPlan]
  def changePlan(orgId: Id[Organization], newPlan: Id[PaidPlan], attribution: ActionAttribution): Try[AccountEvent]
  def getPlanRenewal(orgId: Id[Organization]): DateTime

  def getActivePaymentMethods(orgId: Id[Organization]): Seq[PaymentMethod]
  def getPaymentMethod(paymentMethodId: Id[PaymentMethod]): PaymentMethod
  def addPaymentMethod(orgId: Id[Organization], stripeToken: StripeToken, attribution: ActionAttribution, lastFour: String): PaymentMethod
  def changeDefaultPaymentMethod(orgId: Id[Organization], newDefault: Id[PaymentMethod], attribution: ActionAttribution, lastFour: String): Try[(AccountEvent, Boolean)]
  def getDefaultPaymentMethod(orgId: Id[Organization]): Option[PaymentMethod]
  def getPaymentStatus(orgId: Id[Organization]): PaymentStatus

  //ADMIN ONLY
  def isFrozen(orgId: Id[Organization]): Boolean
  def unfreeze(orgId: Id[Organization]): Option[Boolean]
}

class PlanManagementCommanderImpl @Inject() (
  db: Database,
  paymentMethodRepo: PaymentMethodRepo,
  paidAccountRepo: PaidAccountRepo,
  accountEventRepo: AccountEventRepo,
  paidPlanRepo: PaidPlanRepo,
  orgRepo: OrganizationRepo,
  orgMembershipRepo: OrganizationMembershipRepo,
  orgConfigRepo: OrganizationConfigurationRepo,
  basicUserRepo: BasicUserRepo,
  clock: Clock,
  airbrake: AirbrakeNotifier,
  userRepo: UserRepo,
  accountLockHelper: AccountLockHelper,
  eventTrackingCommander: AccountEventTrackingCommander,
  creditRewardCommander: CreditRewardCommander,
  stripe: StripeClient,
  implicit val defaultContext: ExecutionContext,
  implicit val publicIdConfig: PublicIdConfiguration)
    extends PlanManagementCommander with Logging {

  private val MATH_CONTEXT = new MathContext(34, RoundingMode.HALF_DOWN)

  private def orgId2AccountId(orgId: Id[Organization])(implicit session: RSession): Id[PaidAccount] = {
    paidAccountRepo.getAccountId(orgId)
  }

  //very explicitly accepts a db session to allow account creation on org creation within the same db session
  def remainingBillingCycleCostPerUser(account: PaidAccount, from: DateTime)(implicit session: RSession): DollarAmount = {
    val plan = paidPlanRepo.get(account.planId)
    val cycleLengthMonths = plan.billingCycle.months
    val cycleStart: DateTime = account.planRenewal.minusMonths(cycleLengthMonths)
    val cycleEnd: DateTime = account.planRenewal
    val cycleLengthDays: Double = Days.daysBetween(cycleStart, cycleEnd).getDays.toDouble //note that this is different depending on the current month
    val remaining: Double = Days.daysBetween(from, cycleEnd).getDays.toDouble max 0
    val fraction: Double = remaining / cycleLengthDays
    val fullPrice = new BigDecimal(plan.pricePerCyclePerUser.cents, MATH_CONTEXT)
    val remainingPrice = fullPrice.multiply(new BigDecimal(fraction, MATH_CONTEXT), MATH_CONTEXT).setScale(0, RoundingMode.HALF_DOWN)
    DollarAmount(remainingPrice.intValueExact)
  }

  def remainingBillingCycleCost(account: PaidAccount, from: DateTime)(implicit session: RSession): DollarAmount = remainingBillingCycleCostPerUser(account, from) * account.activeUsers

  //very explicitly accepts a db session to allow account creation on org creation within the same db session
  def createAndInitializePaidAccountForOrganization(orgId: Id[Organization], planId: Id[PaidPlan], creator: Id[User], session: RWSession): Try[AccountEvent] = {
    implicit val s = session
    Try { paidPlanRepo.get(planId) } match {
      case Success(plan) =>
        log.info(s"[PAC] $orgId: Plan exists.")
        if (plan.state != PaidPlanStates.ACTIVE) {
          Failure(new InvalidChange("plan_not_active"))
        } else {
          paidAccountRepo.maybeGetByOrgId(orgId, Set()) match {
            case Some(pa) if pa.state == PaidAccountStates.ACTIVE =>
              log.info(s"[PAC] $orgId: Account already exists.")
              Failure(new InvalidChange("account_exists"))
            case inactiveAccountMaybe =>
              log.info(s"[PAC] $orgId: Creating Account...")
              val account = paidAccountRepo.save(PaidAccount(
                id = inactiveAccountMaybe.flatMap(_.id),
                orgId = orgId,
                planId = planId,
                credit = DollarAmount.ZERO,
                planRenewal = PlanRenewalPolicy.newPlansStartDate(clock.now()),
                userContacts = Seq(creator),
                emailContacts = Seq.empty,
                activeUsers = 0
              ))

              val attribution = ActionAttribution(user = Some(creator), admin = None)

              val creationEvent = eventTrackingCommander.track(AccountEvent.simpleNonBillingEvent(
                eventTime = clock.now,
                accountId = account.id.get,
                attribution = attribution,
                action = AccountEventAction.OrganizationCreated(planId, Some(account.planRenewal))
              ))

              log.info(s"[PAC] $orgId: Granting initial rewards...")
              creditRewardCommander.initializeRewards(orgId)

              log.info(s"[PAC] $orgId: Registering owner...")
              registerNewUser(orgId, creator, OrganizationRole.ADMIN, attribution)

              log.info(s"[PAC] $orgId: Account successfully created!")
              Success(creationEvent)

          }
        }
      case Failure(ex) =>
        log.error(s"[PAC] $orgId: Plan does not exist!", ex)
        airbrake.notify("Paid Plan Not available!!", ex)
        Failure(new InvalidChange("plan_not_available"))
    }

  }

  def deactivatePaidAccountForOrganization(orgId: Id[Organization], session: RWSession): Try[Unit] = {
    implicit val s = session
    Try {
      paidAccountRepo.maybeGetByOrgId(orgId).foreach { account =>
        paidAccountRepo.deactivate(account)
        accountEventRepo.deactivateAll(account.id.get)
        paymentMethodRepo.getByAccountId(account.id.get).foreach(paymentMethodRepo.deactivate)
      }
    }
  }

  def registerNewUser(orgId: Id[Organization], userId: Id[User], role: OrganizationRole, attribution: ActionAttribution, overrideLock: Boolean = false)(implicit session: RWSession): AccountEvent = {
    def doRegisterNewUser = {
      val account = paidAccountRepo.getByOrgId(orgId)
      val now = clock.now()
      val price: DollarAmount = remainingBillingCycleCostPerUser(account, from = now)
      paidAccountRepo.save(
        account.withReducedCredit(price).withMoreActiveUsers(1)
      )
      eventTrackingCommander.track(AccountEvent.simpleNonBillingEvent(
        eventTime = now,
        accountId = account.id.get,
        attribution = attribution,
        action = AccountEventAction.UserJoinedOrganization(userId, role),
        creditChange = -price
      ))
    }

    if (overrideLock) { doRegisterNewUser }
    else accountLockHelper.maybeWithAccountLock(orgId) {
      doRegisterNewUser
    } getOrElse {
      throw new LockedAccountException(orgId)
    }
  }

  def registerRemovedUser(orgId: Id[Organization], userId: Id[User], role: OrganizationRole, attribution: ActionAttribution, overrideLock: Boolean = false)(implicit session: RWSession): AccountEvent = {
    def doRegisterRemovedUser = {
      val account = paidAccountRepo.getByOrgId(orgId)
      val now = clock.now()
      val price: DollarAmount = remainingBillingCycleCostPerUser(account, from = now)
      val newAccount = account.withIncreasedCredit(price).withFewerActiveUsers(1).withUserContacts(account.userContacts.diff(Seq(userId)))

      paidAccountRepo.save(newAccount)
      eventTrackingCommander.track(AccountEvent.simpleNonBillingEvent(
        eventTime = now,
        accountId = orgId2AccountId(orgId),
        attribution = attribution,
        action = AccountEventAction.UserLeftOrganization(userId, role),
        creditChange = price
      ))
    }
    if (overrideLock) { doRegisterRemovedUser }
    else accountLockHelper.maybeWithAccountLock(orgId) {
      doRegisterRemovedUser
    } getOrElse {
      throw new LockedAccountException(orgId)
    }
  }

  def registerRoleChanged(orgId: Id[Organization], userId: Id[User], from: OrganizationRole, to: OrganizationRole, attribution: ActionAttribution)(implicit session: RWSession): AccountEvent = {
    eventTrackingCommander.track(AccountEvent.simpleNonBillingEvent(
      eventTime = clock.now,
      accountId = orgId2AccountId(orgId),
      attribution = attribution,
      action = AccountEventAction.OrganizationRoleChanged(userId, from = from, to = to)
    ))
  }

  def removeUserAccountContact(orgId: Id[Organization], userId: Id[User], attribution: ActionAttribution): Option[AccountEvent] = db.readWrite { implicit session =>
    accountLockHelper.maybeWithAccountLock(orgId) {
      val account = paidAccountRepo.getByOrgId(orgId)
      val org = orgRepo.get(orgId)
      if (account.userContacts.contains(userId) && userId != org.ownerId) {
        val updatedAccount = account.copy(userContacts = account.userContacts.filter(_ != userId))
        paidAccountRepo.save(updatedAccount)
        Some(eventTrackingCommander.track(AccountEvent.simpleNonBillingEvent(
          eventTime = clock.now,
          accountId = updatedAccount.id.get,
          attribution = attribution,
          action = AccountEventAction.AccountContactsChanged(userAdded = None, userRemoved = Some(userId), emailAdded = None, emailRemoved = None)
        )))
      } else None
    } getOrElse { throw new LockedAccountException(orgId) }
  }

  def removeEmailAccountContact(orgId: Id[Organization], emailAddress: EmailAddress, attribution: ActionAttribution): Option[AccountEvent] = db.readWrite { implicit session =>
    accountLockHelper.maybeWithAccountLock(orgId) {
      val account = paidAccountRepo.getByOrgId(orgId)
      if (account.emailContacts.contains(emailAddress)) {
        val updatedAccount = account.copy(emailContacts = account.emailContacts.filter(_ != emailAddress))
        paidAccountRepo.save(updatedAccount)
        Some(eventTrackingCommander.track(AccountEvent.simpleNonBillingEvent(
          eventTime = clock.now,
          accountId = updatedAccount.id.get,
          attribution = attribution,
          action = AccountEventAction.AccountContactsChanged(userAdded = None, userRemoved = None, emailAdded = None, emailRemoved = Some(emailAddress))
        )))
      } else None
    } getOrElse { throw new LockedAccountException(orgId) }
  }

  def addUserAccountContact(orgId: Id[Organization], userId: Id[User], attribution: ActionAttribution): Option[AccountEvent] = db.readWrite { implicit session =>
    addUserAccountContactHelper(orgId, userId, attribution)
  }

  def addUserAccountContactHelper(orgId: Id[Organization], userId: Id[User], attribution: ActionAttribution)(implicit session: RWSession): Option[AccountEvent] = {
    accountLockHelper.maybeWithAccountLock(orgId) {
      val account = paidAccountRepo.getByOrgId(orgId)
      if (!account.userContacts.contains(userId)) {
        val updatedAccount = account.copy(userContacts = account.userContacts :+ userId)
        paidAccountRepo.save(updatedAccount)
        Some(eventTrackingCommander.track(AccountEvent.simpleNonBillingEvent(
          eventTime = clock.now,
          accountId = updatedAccount.id.get,
          attribution = attribution,
          action = AccountEventAction.AccountContactsChanged(userAdded = Some(userId), userRemoved = None, emailAdded = None, emailRemoved = None)
        )))
      } else None
    } getOrElse { throw new LockedAccountException(orgId) }
  }

  def addEmailAccountContact(orgId: Id[Organization], emailAddress: EmailAddress, attribution: ActionAttribution): Option[AccountEvent] = db.readWrite { implicit session =>
    accountLockHelper.maybeWithAccountLock(orgId) {
      val account = paidAccountRepo.getByOrgId(orgId)
      if (!account.emailContacts.contains(emailAddress)) {
        val updatedAccount = account.copy(emailContacts = account.emailContacts.filter(_ != emailAddress) :+ emailAddress)
        paidAccountRepo.save(updatedAccount)
        Some(eventTrackingCommander.track(AccountEvent.simpleNonBillingEvent(
          eventTime = clock.now,
          accountId = updatedAccount.id.get,
          attribution = attribution,
          action = AccountEventAction.AccountContactsChanged(userAdded = None, userRemoved = None, emailAdded = Some(emailAddress), emailRemoved = None)
        )))
      } else None
    } getOrElse { throw new LockedAccountException(orgId) }
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

  private def grantSpecialCreditHelper(orgId: Id[Organization], amount: DollarAmount, grantedByAdmin: Option[Id[User]], attributedToMember: Option[Id[User]], memo: Option[String])(implicit session: RWSession): AccountEvent = {
    val account = paidAccountRepo.getByOrgId(orgId)
    paidAccountRepo.save(account.withIncreasedCredit(amount))
    eventTrackingCommander.track(AccountEvent(
      eventTime = clock.now(),
      accountId = account.id.get,
      whoDunnit = attributedToMember,
      whoDunnitExtra = JsNull,
      kifiAdminInvolved = grantedByAdmin,
      action = AccountEventAction.SpecialCredit(),
      creditChange = amount,
      paymentMethod = None,
      paymentCharge = None,
      memo = memo,
      chargeId = None
    ))
  }

  def grantSpecialCredit(orgId: Id[Organization], amount: DollarAmount, grantedByAdmin: Option[Id[User]], attributedToMember: Option[Id[User]], memo: Option[String]): AccountEvent = accountLockHelper.maybeSessionWithAccountLock(orgId, attempts = 3) { implicit session =>
    grantSpecialCreditHelper(orgId, amount, grantedByAdmin, attributedToMember, memo)
  }.get

  def getCurrentCredit(orgId: Id[Organization]): DollarAmount = db.readOnlyMaster { implicit session =>
    paidAccountRepo.getByOrgId(orgId).credit
  }

  def getAccountState(orgId: Id[Organization]): Future[AccountStateResponse] = {
    val (account, plan) = db.readOnlyReplica { implicit session =>
      val account = paidAccountRepo.getByOrgId(orgId)
      val plan = paidPlanRepo.get(account.planId)
      (account, plan)
    }
    val cardFut = getDefaultPaymentMethod(orgId).map { method =>
      stripe.getCardInfo(method.stripeToken).map(info => Some(CardInfo(method.id.get, info))).recover {
        case ex: APIException => None
      }
    }.getOrElse(Future.successful(None))

    cardFut.map { card =>
      AccountStateResponse(account, plan, card)
    }
  }

  def previewAccountState(orgId: Id[Organization], newPlanId: Id[PaidPlan], newPaymentMethodId: Id[PaymentMethod]): Future[AccountStateResponse] = {
    val newPaymentMethod = db.readOnlyMaster { implicit session =>
      paymentMethodRepo.get(newPaymentMethodId)
    }

    val futureCardInfo = stripe.getCardInfo(newPaymentMethod.stripeToken).map(info => Some(CardInfo(newPaymentMethodId, info))).recover { case ex: APIException => None }

    val (accountPreview, planPreview) = db.readOnlyReplica { implicit session =>
      val currentAccount = paidAccountRepo.getByOrgId(orgId)
      val accountPreview = if (currentAccount.planId == newPlanId) currentAccount
      else {
        val now = clock.now()
        val newPlanStartDate = PlanRenewalPolicy.newPlansStartDate(now)
        val currentPlanRefund = remainingBillingCycleCost(currentAccount, from = newPlanStartDate)
        val paymentStatus = if (currentAccount.paymentStatus == PaymentStatus.Failed) PaymentStatus.Pending else currentAccount.paymentStatus // abusing "Pending" semantics in this API
        currentAccount.withNewPlan(newPlanId).withIncreasedCredit(currentPlanRefund).withPlanRenewal(newPlanStartDate).withPaymentStatus(paymentStatus)
      }
      val planPreview = paidPlanRepo.get(newPlanId)
      (accountPreview, planPreview)
    }
    futureCardInfo.map { cardInfo => AccountStateResponse(accountPreview, planPreview, cardInfo) }
  }

  def currentPlan(orgId: Id[Organization]): PaidPlan = db.readOnlyMaster { implicit session =>
    currentPlanHelper(orgId)
  }

  def currentPlanHelper(orgId: Id[Organization])(implicit session: RSession): PaidPlan = {
    val account = paidAccountRepo.getByOrgId(orgId)
    paidPlanRepo.get(account.planId)
  }

  def createNewPlan(name: Name[PaidPlan], displayName: String, billingCycle: BillingCycle, price: DollarAmount, custom: Boolean = false, editableFeatures: Set[Feature], defaultSettings: OrganizationSettings): PaidPlan = {
    db.readWrite { implicit session => createNewPlanHelper(name, displayName, billingCycle, price, custom, editableFeatures, defaultSettings) }
  }

  def createNewPlanHelper(name: Name[PaidPlan], displayName: String, billingCycle: BillingCycle, price: DollarAmount, custom: Boolean = false, editableFeatures: Set[Feature], defaultSettings: OrganizationSettings)(implicit session: RWSession): PaidPlan = {
    paidPlanRepo.save(PaidPlan(kind = if (custom) PaidPlan.Kind.CUSTOM else PaidPlan.Kind.NORMAL,
      name = name,
      displayName = displayName,
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

  def getCurrentAndAvailablePlans(orgId: Id[Organization]): (Id[PaidPlan], Set[PaidPlan]) = db.readOnlyReplica { implicit session =>
    val currentPlan = currentPlanHelper(orgId)
    val currentPlans = paidPlanRepo.getByDisplayName(currentPlan.displayName) // get plans with same name, different billing cycles
    val normalPlans = paidPlanRepo.getByKinds(Set(PaidPlan.Kind.NORMAL))
    (currentPlan.id.get, normalPlans.toSet ++ currentPlans)
  }

  def changePlan(orgId: Id[Organization], newPlanId: Id[PaidPlan], attribution: ActionAttribution): Try[AccountEvent] = accountLockHelper.maybeSessionWithAccountLock(orgId, attempts = 2) { implicit session =>
    val account = paidAccountRepo.getByOrgId(orgId)
    if (account.planId != newPlanId) {
      val oldPlan = paidPlanRepo.get(account.planId)
      val newPlan = paidPlanRepo.get(newPlanId)
      val allowedKinds = Set(PaidPlan.Kind.NORMAL) ++ attribution.admin.map(_ => PaidPlan.Kind.CUSTOM) + oldPlan.kind
      if (newPlan.state == PaidPlanStates.ACTIVE && allowedKinds.contains(newPlan.kind)) {
        if (newPlan.editableFeatures != oldPlan.editableFeatures) {
          val oldConfig = orgConfigRepo.getByOrgId(orgId)
          val restrictedFeatures = oldPlan.editableFeatures -- newPlan.editableFeatures
          val restrictedSettings = restrictedFeatures.map(f => f -> newPlan.defaultSettings.settingFor(f).getOrElse {
            throw new RuntimeException(s"${oldPlan.id.get} has a feature ${f.value} that ${newPlan.id.get} does not have a default setting for")
          }).toMap
          val newSettings = oldConfig.settings.setAll(restrictedSettings)
          orgConfigRepo.save(oldConfig.withSettings(newSettings))
        }

        creditRewardCommander.registerRewardTrigger(RewardTrigger.OrganizationUpgraded(orgId, newPlan))

        val now = clock.now()
        val newPlanStartDate = PlanRenewalPolicy.newPlansStartDate(now)
        val refund = remainingBillingCycleCost(account, from = newPlanStartDate)
        val updatedAccount = paidAccountRepo.save(account.withNewPlan(newPlanId).withIncreasedCredit(refund).withPlanRenewal(newPlanStartDate))
        Success(eventTrackingCommander.track(AccountEvent.simpleNonBillingEvent(
          eventTime = now,
          accountId = account.id.get,
          attribution = attribution,
          action = AccountEventAction.PlanChanged(account.planId, newPlanId, Some(updatedAccount.planRenewal)),
          creditChange = refund
        )))
      } else {
        Failure(new InvalidChange("plan_not_available"))
      }
    } else {
      Failure(new InvalidChange("plan_already_selected"))
    }
  }.getOrElse {
    Failure(new Exception("failed_getting_account_lock"))
  }

  def getPlanRenewal(orgId: Id[Organization]): DateTime = db.readOnlyMaster { implicit session =>
    paidAccountRepo.getByOrgId(orgId).planRenewal
  }

  def getActivePaymentMethods(orgId: Id[Organization]): Seq[PaymentMethod] = db.readOnlyMaster { implicit session =>
    paymentMethodRepo.getByAccountId(orgId2AccountId(orgId))
  }

  def getPaymentMethod(paymentMethodId: Id[PaymentMethod]): PaymentMethod = db.readOnlyMaster { implicit session =>
    paymentMethodRepo.get(paymentMethodId)
  }

  def addPaymentMethod(orgId: Id[Organization], stripeToken: StripeToken, attribution: ActionAttribution, lastFour: String): PaymentMethod = db.readWrite { implicit session =>
    val accountId = orgId2AccountId(orgId)
    val newPaymentMethod = paymentMethodRepo.save(PaymentMethod(
      accountId = accountId,
      default = false,
      stripeToken = stripeToken
    ))
    eventTrackingCommander.track(AccountEvent.simpleNonBillingEvent(
      eventTime = clock.now,
      accountId = accountId,
      attribution = attribution,
      action = AccountEventAction.PaymentMethodAdded(newPaymentMethod.id.get, lastFour)
    ))
    newPaymentMethod
  }

  def changeDefaultPaymentMethod(orgId: Id[Organization], newDefaultId: Id[PaymentMethod], attribution: ActionAttribution, newLastFour: String): Try[(AccountEvent, Boolean)] = {
    accountLockHelper.maybeSessionWithAccountLock(orgId, attempts = 2) { implicit session =>
      val account = paidAccountRepo.getByOrgId(orgId)
      val accountId = account.id.get
      val oldDefaultOpt = paymentMethodRepo.getDefault(accountId)
      if (!oldDefaultOpt.flatMap(_.id).contains(newDefaultId)) {
        val newDefault = paymentMethodRepo.get(newDefaultId)
        if (newDefault.state == PaymentMethodStates.ACTIVE && newDefault.accountId == accountId) {
          oldDefaultOpt.map { oldDefault =>
            paymentMethodRepo.save(oldDefault.copy(default = false))
          }
          paymentMethodRepo.save(newDefault.copy(default = true))
          val lastPaymentFailed = account.paymentStatus == PaymentStatus.Failed
          if (lastPaymentFailed) {
            paidAccountRepo.save(account.withPaymentStatus(PaymentStatus.Ok))
          }
          val event = eventTrackingCommander.track(AccountEvent.simpleNonBillingEvent(
            eventTime = clock.now,
            accountId = accountId,
            attribution = attribution,
            action = AccountEventAction.DefaultPaymentMethodChanged(oldDefaultOpt.map(_.id.get), newDefault.id.get, newLastFour)
          ))
          Success((event, lastPaymentFailed))
        } else {
          Failure(new InvalidChange("payment_method_not_available"))
        }
      } else {
        Failure(new InvalidChange("payment_method_already_selected"))
      }
    } getOrElse Failure(LockedAccountException(orgId))
  }

  def getDefaultPaymentMethod(orgId: Id[Organization]): Option[PaymentMethod] = {
    getActivePaymentMethods(orgId).find(_.default)
  }

  def getPaymentStatus(orgId: Id[Organization]): PaymentStatus = db.readOnlyMaster { implicit session =>
    paidAccountRepo.getByOrgId(orgId).paymentStatus
  }

  def isFrozen(orgId: Id[Organization]): Boolean = db.readOnlyMaster { implicit session =>
    paidAccountRepo.getByOrgId(orgId).frozen
  }

  def unfreeze(orgId: Id[Organization]): Option[Boolean] = accountLockHelper.maybeSessionWithAccountLock(orgId, attempts = 2) { implicit session =>
    paidAccountRepo.save(
      paidAccountRepo.getByOrgId(orgId).copy(frozen = false)
    ).frozen
  }
}
