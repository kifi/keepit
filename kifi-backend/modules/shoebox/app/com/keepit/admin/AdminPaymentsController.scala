package com.keepit.controllers.admin

import com.keepit.common.controller.{ AdminUserActions, UserActionsHelper }
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.Database
import com.keepit.common.time.Clock
import com.keepit.common.util.{ DollarAmount, DescriptionElements, PaginationHelper, Paginator }
import com.keepit.model._
import com.keepit.payments._
import com.keepit.common.akka.SafeFuture
import com.keepit.common.db.Id
import org.joda.time.DateTime
import com.keepit.common.time._

import play.api.libs.iteratee.{ Concurrent, Enumerator }
import play.api.libs.json._
import play.api.libs.functional.syntax._
import play.twirl.api.HtmlFormat

import scala.collection.mutable
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Success, Failure }

import com.google.inject.Inject

class AdminPaymentsController @Inject() (
    val userActionsHelper: UserActionsHelper,
    implicit val executionContext: ExecutionContext,
    orgRepo: OrganizationRepo,
    orgMembershipRepo: OrganizationMembershipRepo,
    paidAccountRepo: PaidAccountRepo,
    accountEventRepo: AccountEventRepo,
    userRepo: UserRepo,
    planCommander: PlanManagementCommander,
    paymentProcessingCommander: PaymentProcessingCommander,
    stripeClient: StripeClient,
    integrityChecker: PaymentsIntegrityChecker,
    activityLogCommander: ActivityLogCommander,
    eventTrackingCommander: AccountEventTrackingCommander,
    creditRewardCommander: CreditRewardCommander,
    dashboardCommander: PaymentsDashboardCommander,
    rewardRepo: CreditRewardRepo,
    accountLockHelper: AccountLockHelper,
    clock: Clock,
    db: Database) extends AdminUserActions {

  val EXTRA_SPECIAL_ADMINS: Set[Id[User]] = Set(1, 3, 61, 134).map(Id[User](_))

  def grantExtraCredit(orgId: Id[Organization]) = AdminUserAction { request =>
    val amount = request.body.asFormUrlEncoded.get.apply("amount").head.trim.toInt
    val memo = request.body.asFormUrlEncoded.get.apply("memo").filterNot(_ == "").headOption.map(_.trim)
    val attributedToMember = request.body.asFormUrlEncoded.get.get("member").flatMap(_.headOption.filterNot(_ == "").map(id => Id[User](id.trim.toLong)))
    val dollarAmount = DollarAmount.cents(amount)

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
      case Some(pm) =>
        stripeClient.getLastFourDigitsOfCard(pm.stripeToken).map { lastFour => s"*${lastFour}" }
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
      memo = accountEvent.memo,
      description = activityLogCommander.buildSimpleEventInfoHelper(accountEvent).description
    )
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
      s"Account Activity for ${org.name}", { page: Int => com.keepit.controllers.admin.routes.AdminPaymentsController.getAccountActivity(orgId, page) }.andThen(asPlayHtml),
      page,
      allEvents.length,
      PAGE_SIZE))
  }

  def refundCharge(eventId: Id[AccountEvent]) = AdminUserAction.async { implicit request =>
    paymentProcessingCommander.refundCharge(eventId, request.userId).map {
      case (account, _) =>
        Redirect(com.keepit.controllers.admin.routes.AdminPaymentsController.getAccountActivity(account.orgId, 0))
    }
  }

  def unfreezeAccount(orgId: Id[Organization]) = AdminUserAction { implicit request =>
    Ok(planCommander.unfreeze(orgId).toString)
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
    Ok(views.html.admin.paymentsDashboard(dashboardCommander.generateDashboard()))
  }

  def checkIntegrity(orgId: Id[Organization], doIt: Boolean) = AdminUserAction.async { implicit request =>
    if (doIt) {
      SafeFuture {
        val errors = integrityChecker.checkAccount(orgId)
        val result = Json.toJson(errors)
        Ok(result)
      }
    } else {
      Future.successful(BadRequest("You don't want to do this."))
    }
  }

  def createCode() = AdminUserAction(parse.tolerantJson) { implicit request =>
    val req = request.body.as[CreditCodeAdminCreateRequest]
    val newCode = creditRewardCommander.adminCreateCreditCode(CreditCodeInfo(
      code = req.code,
      kind = req.kind,
      credit = req.credit,
      status = CreditCodeStatus.Open,
      referrer = None
    ))
    Ok(Json.obj("created" -> Json.obj("code" -> newCode.code, "kind" -> newCode.kind.kind, "value" -> DollarAmount.formatAsCents.writes(newCode.credit))))
  }
}

case class CreditCodeAdminCreateRequest(
  kind: CreditCodeKind,
  code: CreditCode,
  credit: DollarAmount)

object CreditCodeAdminCreateRequest {
  implicit val reads: Reads[CreditCodeAdminCreateRequest] = (
    (__ \ 'kind).read[CreditCodeKind](CreditCodeKind.reads) and
    (__ \ 'code).read[CreditCode] and
    (__ \ 'credit).read[DollarAmount](DollarAmount.formatAsCents)
  )(CreditCodeAdminCreateRequest.apply _)
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
  memo: Option[String],
  description: DescriptionElements)

case class AdminAccountView(
  organization: Organization)

case class AdminPaymentsActivityOverview(
  events: Seq[AdminAccountEventView],
  orgsByAccountId: Map[Id[PaidAccount], Organization],
  pgHelper: PaginationHelper)

