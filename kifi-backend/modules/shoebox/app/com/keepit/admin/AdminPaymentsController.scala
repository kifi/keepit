package com.keepit.controllers.admin

import com.keepit.common.controller.{ AdminUserActions, UserActionsHelper }
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.Database
import com.keepit.common.time.Clock
import com.keepit.common.util.{ PaginationHelper, Paginator }
import com.keepit.model._
import com.keepit.payments._
import com.keepit.common.akka.SafeFuture
import com.keepit.common.db.Id
import org.joda.time.DateTime

import play.api.libs.iteratee.{ Concurrent, Enumerator }
import play.api.libs.json.{ Json, JsArray }
import play.twirl.api.HtmlFormat

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Success, Failure }

import com.google.inject.Inject

class AdminPaymentsController @Inject() (
    val userActionsHelper: UserActionsHelper,
    implicit val executionContext: ExecutionContext,
    orgRepo: OrganizationRepo,
    orgMembershipRepo: OrganizationMembershipRepo,
    paidPlanRepo: PaidPlanRepo,
    paidAccountRepo: PaidAccountRepo,
    accountEventRepo: AccountEventRepo,
    orgConfigRepo: OrganizationConfigurationRepo,
    userRepo: UserRepo,
    planCommander: PlanManagementCommander,
    paymentProcessingCommander: PaymentProcessingCommander,
    stripeClient: StripeClient,
    clock: Clock,
    db: Database) extends AdminUserActions {

  val EXTRA_SPECIAL_ADMINS: Set[Id[User]] = Set(1, 3, 61, 134).map(Id[User](_))

  def backfillPaidAccounts = AdminUserAction { request =>
    def printStackTraceToChannel(t: Throwable, channel: Concurrent.Channel[String]) = {
      val stackTrace = t.getStackTrace().toSeq
      stackTrace.foreach { ste =>
        channel.push(s">> ${ste.toString}\n")
      }
    }

    def processAndReport(channel: Concurrent.Channel[String]) = {
      val orgs = db.readOnlyMaster { implicit session =>
        orgRepo.all()
      }.filter(_.state == OrganizationStates.ACTIVE)
      channel.push(s"All ${orgs.size} organizations loaded\n")
      orgs.foreach { org =>
        channel.push("----------------------------------------\n")
        channel.push(s"Processing org ${org.id.get}: ${org.name}\n")
        db.readWrite { implicit session =>
          paidAccountRepo.maybeGetByOrgId(org.id.get) match {
            case Some(_) =>
              channel.push(s"Paid account already exists. Doing nothing.\n")
            case None =>
              planCommander.createAndInitializePaidAccountForOrganization(org.id.get, PaidPlan.DEFAULT, request.userId, session) match {
                case Success(event) =>
                  channel.push(s"Successfully created paid account for org ${org.id.get}\n")
                  channel.push(event.toString + "\n")
                case Failure(ex) =>
                  channel.push(s"Failed creating paid account for org ${org.id.get}: ${ex.getMessage}\n")
                  printStackTraceToChannel(ex, channel)
              }
          }
        }
        Thread.sleep(200)
      }
    }

    val enum: Enumerator[String] = Concurrent.unicast(onStart = (channel: Concurrent.Channel[String]) =>
      SafeFuture("Paid Account Backfill") {
        processAndReport(channel)
        channel.eofAndEnd()
      }
    )
    Ok.chunked(enum)
  }

  def grantExtraCredit(orgId: Id[Organization]) = AdminUserAction { request =>
    val amount = request.body.asFormUrlEncoded.get.apply("amount").head.trim.toInt
    val memo = request.body.asFormUrlEncoded.get.apply("memo").filterNot(_ == "").headOption.map(_.trim)
    val attributedToMember = request.body.asFormUrlEncoded.get.get("member").flatMap(_.headOption.filterNot(_ == "").map(id => Id[User](id.trim.toLong)))
    val dollarAmount = DollarAmount(amount)

    val isAttributedToNonMember = db.readOnlyMaster { implicit session =>
      val org = orgRepo.get(orgId) //lets see if its actually a good id
      assert(org.state == OrganizationStates.ACTIVE, s"Org state is not active $org")
      val isAttributedToNonMember = attributedToMember.exists(userId => orgMembershipRepo.getByOrgIdAndUserId(orgId, userId).isEmpty)
      isAttributedToNonMember
    }

    if ((amount < 0 || amount > 10000) && !EXTRA_SPECIAL_ADMINS.contains(request.userId)) {
      Ok("You are not special enough to deduct credit or grant more than $100.")
    } else if (amount == 0) {
      Ok("Umm, 0 credit?")
    } else if (isAttributedToNonMember) {
      Ok(s"User ${attributedToMember.get} is not a member of Organization $orgId")
    } else {
      planCommander.grantSpecialCredit(orgId, dollarAmount, Some(request.userId), attributedToMember, memo)
      Ok(s"Successfully granted special credit of $dollarAmount to Organization $orgId.")
    }
  }

  def processOrgNow(orgId: Id[Organization]) = AdminUserAction.async { request =>
    val account = db.readOnlyMaster { implicit session => paidAccountRepo.getByOrgId(orgId) }
    paymentProcessingCommander.processAccount(account).map {
      case (_, event) =>
        val result = Json.obj(event.action.eventType.value -> event.creditChange.toDollarString)
        Ok(result)
    }
  }

  def changePlanForOrg(orgId: Id[Organization]) = AdminUserAction { request =>
    val newPlan = Id[PaidPlan](request.body.asFormUrlEncoded.get.apply("planId").head.toInt)
    planCommander.changePlan(orgId, newPlan, ActionAttribution(user = None, admin = Some(request.userId))) match {
      case Success(res) => Ok(res.toString)
      case Failure(ex) => Ok(ex.toString)
    }
  }

  def addCreditCardView(orgId: Id[Organization]) = AdminUserAction.async { request =>
    val currentCardFuture = planCommander.getDefaultPaymentMethod(orgId) match {
      case Some(pm) => {
        stripeClient.getLastFourDigitsOfCard(pm.stripeToken).map { lastFour => s"*${lastFour}" }
      }
      case None => Future.successful("N/A")
    }
    currentCardFuture.map { lastFour =>
      Ok(views.html.admin.addCreditCard(orgId, lastFour))
    }
  }

  def addCreditCard(orgId: Id[Organization]) = AdminUserAction.async { request =>
    val data = request.body.asFormUrlEncoded.get
    val cardDetails = CardDetails(
      data("number").head.trim,
      data("expMonth").head.trim.toInt,
      data("expYear").head.trim.toInt,
      data("cvc").head.trim,
      data("cardholderName").head.trim
    )
    stripeClient.getPermanentToken(cardDetails, s"Manually Entered through admin ${request.userId} for org $orgId").map { token =>
      val lastFour = cardDetails.number.takeRight(4)
      val pm = planCommander.addPaymentMethod(orgId, token, ActionAttribution(user = None, admin = Some(request.userId)), lastFour)
      val event = planCommander.changeDefaultPaymentMethod(orgId, pm.id.get, ActionAttribution(user = None, admin = Some(request.userId)), lastFour)
      Ok(event.toString)
    }
  }

  private def createAdminAccountView(account: PaidAccount)(implicit session: RSession): AdminAccountView = {
    AdminAccountView(
      organization = orgRepo.get(account.orgId)
    )
  }
  private def createAdminAccountEventView(accountEvent: AccountEvent)(implicit session: RSession): AdminAccountEventView = {
    val (userWhoDunnit, adminInvolved) = {
      (accountEvent.whoDunnit.map(userRepo.get), accountEvent.kifiAdminInvolved.map(userRepo.get))
    }
    AdminAccountEventView(
      id = accountEvent.id.get,
      accountId = accountEvent.accountId,
      action = accountEvent.action,
      eventTime = accountEvent.eventTime,
      whoDunnit = userWhoDunnit,
      adminInvolved = adminInvolved,
      creditChange = accountEvent.creditChange,
      paymentCharge = accountEvent.paymentCharge,
      memo = accountEvent.memo)
  }

  private def asPlayHtml(obj: Any) = HtmlFormat.raw(obj.toString)

  def getAccountActivity(orgId: Id[Organization], page: Int) = AdminUserAction { implicit request =>
    val PAGE_SIZE = 50
    val (allEvents, pagedEvents, org) = db.readOnlyMaster { implicit s =>
      val account = paidAccountRepo.getByOrgId(orgId)
      val allEvents = accountEventRepo.getAllByAccount(account.id.get)
      val pagedEvents = allEvents.drop(page * PAGE_SIZE).take(PAGE_SIZE).map(createAdminAccountEventView)
      val org = orgRepo.get(orgId)
      (allEvents, pagedEvents, org)
    }
    Ok(views.html.admin.accountActivity(
      orgId,
      pagedEvents,
      s"Account Activity for ${org.name}",
      { page: Int => com.keepit.controllers.admin.routes.AdminPaymentsController.getAccountActivity(orgId, page) }.andThen(asPlayHtml),
      page,
      allEvents.length,
      PAGE_SIZE))
  }

  def unfreezeAccount(orgId: Id[Organization]) = AdminUserAction { implicit request =>
    Ok(planCommander.unfreeze(orgId).toString)
  }

  def addOrgOwnersAsBillingContacts() = AdminUserAction { implicit request =>
    db.readWrite { implicit session =>
      orgRepo.allActive.foreach { org =>
        planCommander.addUserAccountContactHelper(org.id.get, org.ownerId, ActionAttribution(user = None, admin = request.adminUserId))
      }
    }
    Ok
  }

  def activityOverview(page: Int, kind: Option[String]) = AdminUserPage { implicit request =>
    val pg = Paginator(num = page, size = 100)
    val kindFilterOpt = kind.flatMap(AccountEventKind.get)
    val activityOverview = db.readOnlyMaster { implicit session =>
      val kinds = kindFilterOpt.map(Set(_)).getOrElse(AccountEventKind.all)
      val eventCount = accountEventRepo.adminCountByKind(kinds)
      val pagedEvents = accountEventRepo.adminGetByKind(kinds, pg).map(createAdminAccountEventView)
      val paginationHelper = PaginationHelper(page = pg.num, itemCount = eventCount, pageSize = pg.size, otherPagesRoute = { p: Int =>
        asPlayHtml(com.keepit.controllers.admin.routes.AdminPaymentsController.activityOverview(p, kind))
      })
      val orgsByAccountId = {
        val accountIds = pagedEvents.map(_.accountId).toSet
        val accountsById = paidAccountRepo.getActiveByIds(accountIds)
        val orgIds = accountsById.values.map(_.orgId).toSet
        val orgsById = orgRepo.getByIds(orgIds)
        accountIds.map { accountId => accountId -> orgsById(accountsById(accountId).orgId) }.toMap
      }
      AdminPaymentsActivityOverview(pagedEvents, orgsByAccountId, paginationHelper)
    }
    Ok(views.html.admin.activityOverview(activityOverview))
  }

  def paymentsDashboard() = AdminUserAction { implicit request =>
    val dashboard = db.readOnlyMaster { implicit session =>
      val frozenAccounts = paidAccountRepo.all.filter(_.frozen).take(100).map(createAdminAccountView) // God help us if we have more than 100 frozen accounts
      val planEnrollment = {
        val planEnrollmentById = paidAccountRepo.getCountsByPlan
        planEnrollmentById.map { case (planId, x) => paidPlanRepo.get(planId) -> x }
      }
      val totalAmortizedIncomePerMonth = {
        val income = planEnrollment.keys.map { plan =>
          val usersOnPlan = planEnrollment(plan)._2
          plan.pricePerCyclePerUser.toCents * usersOnPlan / plan.billingCycle.months
        }.sum
        DollarAmount.cents(income)
      }
      AdminPaymentsDashboard(totalAmortizedIncomePerMonth, planEnrollment, frozenAccounts)
    }
    Ok(views.html.admin.paymentsDashboard(dashboard))
  }
}

case class AdminAccountEventView(
  id: Id[AccountEvent],
  accountId: Id[PaidAccount],
  action: AccountEventAction,
  eventTime: DateTime,
  whoDunnit: Option[User],
  adminInvolved: Option[User],
  creditChange: DollarAmount,
  paymentCharge: Option[DollarAmount],
  memo: Option[String])

case class AdminAccountView(
  organization: Organization)

case class AdminPaymentsActivityOverview(
  events: Seq[AdminAccountEventView],
  orgsByAccountId: Map[Id[PaidAccount], Organization],
  pgHelper: PaginationHelper)

case class AdminPaymentsDashboard(
  totalAmortizedIncomePerMonth: DollarAmount,
  planEnrollment: Map[PaidPlan, (Int, Int)],
  frozenAccounts: Seq[AdminAccountView])
