package com.keepit.controllers.admin

import com.keepit.common.controller.{ AdminUserActions, UserActionsHelper }
import com.keepit.common.db.slick.Database
import com.keepit.model.{ OrganizationRepo, OrganizationStates, Organization, User }
import com.keepit.payments._
import com.keepit.common.akka.SafeFuture
import com.keepit.common.db.Id

import play.api.libs.iteratee.{ Concurrent, Enumerator }

import scala.concurrent.ExecutionContext
import scala.util.{ Success, Failure }

import com.google.inject.Inject

class AdminPaymentsController @Inject() (
    val userActionsHelper: UserActionsHelper,
    implicit val executionContext: ExecutionContext,
    organizationRepo: OrganizationRepo,
    paidAccountRepo: PaidAccountRepo,
    planCommander: PlanManagementCommander,
    paymentProcessingCommander: PaymentProcessingCommander,
    stripeClient: StripeClient,
    db: Database) extends AdminUserActions {

  val EXTRA_SPECIAL_ADMINS: Set[Id[User]] = Set(1, 243).map(Id[User](_))

  def backfillPaidAccounts = AdminUserAction { request =>
    def printStackTraceToChannel(t: Throwable, channel: Concurrent.Channel[String]) = {
      val stackTrace = t.getStackTrace().toSeq
      stackTrace.foreach { ste =>
        channel.push(s">> ${ste.toString}\n")
      }
    }

    def processAndReport(channel: Concurrent.Channel[String]) = {
      val orgs = db.readOnlyMaster { implicit session =>
        organizationRepo.all()
      }.filter(_.state == OrganizationStates.ACTIVE)
      channel.push(s"All ${orgs.size} organizations loaded\n")
      orgs.foreach { org =>
        channel.push("----------------------------------------\n")
        channel.push(s"Processing org ${org.id.get}: ${org.name}\n")
        db.readWrite { implicit session =>
          paidAccountRepo.maybeGetByOrgId(org.id.get) match {
            case Some(_) => {
              channel.push(s"Paid account already exists. Doing nothing.\n")
            }
            case None => {
              planCommander.createAndInitializePaidAccountForOrganization(org.id.get, PaidPlan.DEFAULT, request.userId, session) match {
                case Success(event) => {
                  channel.push(s"Successfully created paid account for org ${org.id.get}\n")
                  channel.push(event.toString + "\n")
                }
                case Failure(ex) => {
                  channel.push(s"Failed creating paid account for org ${org.id.get}: ${ex.getMessage}\n")
                  printStackTraceToChannel(ex, channel)
                }
              }
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
    val amount = request.body.asFormUrlEncoded.get.apply("amount").head.toInt
    val passphrase = request.body.asFormUrlEncoded.get.apply("passphrase").head.toString
    val memoRaw = request.body.asFormUrlEncoded.get.apply("memo").head.toString
    val memo = if (memoRaw == "") None else Some(memoRaw)
    val dollarAmount = DollarAmount(amount)

    val org = db.readOnlyMaster { implicit session => organizationRepo.get(orgId) }

    val passphraseCorrect: Boolean = org.primaryHandle.exists(handle => handle.normalized.value == passphrase.reverse)

    if ((amount < 0 || amount > 10000) && !(EXTRA_SPECIAL_ADMINS.contains(request.userId))) {
      Ok("You are not special enough to deduct credit or grant more than $100.")
    } else if (amount == 0) {
      Ok("Umm, 0 credit?")
    } else if (!passphraseCorrect) {
      Ok("So sorry, but your passphrase isn't right.")
    } else {
      planCommander.grantSpecialCredit(orgId, dollarAmount, Some(request.userId), memo)
      Ok(s"Sucessfully granted special credit of $dollarAmount to Organization $orgId.")
    }
  }

  def processOrgNow(orgId: Id[Organization]) = AdminUserAction.async { request =>
    val account = db.readOnlyMaster { implicit session => paidAccountRepo.getByOrgId(orgId) }
    paymentProcessingCommander.processAccount(account).map { charged =>
      Ok(charged.toString)
    }
  }

  def changePlanForOrg(orgId: Id[Organization]) = AdminUserAction { request =>
    val newPlan = Id[PaidPlan](request.body.asFormUrlEncoded.get.apply("planId").head.toInt)
    planCommander.changePlan(orgId, newPlan, ActionAttribution(user = None, admin = Some(request.userId))) match {
      case Success(res) => Ok(res.toString)
      case Failure(ex) => Ok(ex.toString)
    }
  }

  def addCreditCardView(orgId: Id[Organization]) = AdminUserAction { request =>
    Ok(views.html.admin.addCreditCard(orgId))
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
      val pm = planCommander.addPaymentMethod(orgId, token, ActionAttribution(user = None, admin = Some(request.userId)))
      val event = planCommander.changeDefaultPaymentMethod(orgId, pm.id.get, ActionAttribution(user = None, admin = Some(request.userId)))
      Ok(event.toString)
    }
  }

}
