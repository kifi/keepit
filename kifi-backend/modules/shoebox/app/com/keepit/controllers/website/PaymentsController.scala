package com.keepit.controllers.website

import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.controller.{ UserActions, ShoeboxServiceController, UserActionsHelper }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.util.DollarAmount
import com.keepit.model.ClassFeature.{ Blacklist, SlackIngestionDomainBlacklist }
import com.keepit.shoebox.controllers.OrganizationAccessActions
import com.keepit.model._
import com.keepit.commanders.{ OrganizationInfoCommander, PermissionCommander, OrganizationCommander, OrganizationMembershipCommander, OrganizationInviteCommander }
import com.keepit.payments._
import com.keepit.common.core._
import com.keepit.slack.SlackIngestingBlacklist

import play.api.libs.json.{ Json, JsSuccess, JsError }

import scala.collection.mutable.ListBuffer
import scala.util.{ Success, Failure }
import scala.concurrent.{ ExecutionContext, Future }

import com.google.inject.{ Inject, Singleton }

import org.joda.time.DateTime

@Singleton
class PaymentsController @Inject() (
    planCommander: PlanManagementCommander,
    paymentCommander: PaymentProcessingCommander,
    activityLogCommander: ActivityLogCommander,
    creditRewardCommander: CreditRewardCommander,
    creditRewardInfoCommander: CreditRewardInfoCommander,
    stripeClient: StripeClient,
    val userActionsHelper: UserActionsHelper,
    val db: Database,
    val permissionCommander: PermissionCommander,
    implicit val publicIdConfig: PublicIdConfiguration,
    implicit val ec: ExecutionContext) extends UserActions with OrganizationAccessActions with ShoeboxServiceController {

  def getAccountState(pubId: PublicId[Organization]) = OrganizationUserAction(pubId, OrganizationPermission.MANAGE_PLAN).async { request =>
    planCommander.getAccountState(request.orgId).map { response => Ok(Json.toJson(response)) }
  }

  def previewAccountState(pubId: PublicId[Organization], newPlanId: PublicId[PaidPlan], newCardId: PublicId[PaymentMethod]) = OrganizationUserAction(pubId, OrganizationPermission.MANAGE_PLAN).async { request =>
    (PaidPlan.decodePublicId(newPlanId), PaymentMethod.decodePublicId(newCardId)) match {
      case (Success(planId), Success(cardId)) => planCommander.previewAccountState(request.orgId, planId, cardId).map { response => Ok(Json.toJson(response)) }
      case _ => Future.successful(OrganizationFail.INVALID_PUBLIC_ID.asErrorResponse)
    }
  }

  def updateAccountState(pubId: PublicId[Organization], newPlanId: PublicId[PaidPlan], newCardId: PublicId[PaymentMethod]) = OrganizationUserAction(pubId, OrganizationPermission.MANAGE_PLAN).async { request =>
    (PaidPlan.decodePublicId(newPlanId), PaymentMethod.decodePublicId(newCardId)) match {
      case (Success(planId), Success(cardId)) =>
        if (!planCommander.getActivePaymentMethods(request.orgId).flatMap(_.id).contains(cardId)) Future.successful(BadRequest(Json.obj("error" -> "invalid_card")))
        else {
          val attribution = ActionAttribution(request.request.userIdOpt, None)
          val (currentPlanId, _) = planCommander.getCurrentAndAvailablePlans(request.orgId)
          val updatedPlan = if (planId == currentPlanId) Success(()) else planCommander.changePlan(request.orgId, planId, attribution).map(_ => ())
          updatedPlan match {
            case Success(_) =>
              val futureCharge = {
                if (planCommander.getDefaultPaymentMethod(request.orgId).flatMap(_.id).contains(cardId)) Future.successful(None)
                else doSetDefaultCreditCardAndChargeMaybe(request.orgId, cardId, attribution).imap(_._2)
              }
              futureCharge.flatMap { chargeMaybe =>
                planCommander.getAccountState(request.orgId).map { accountState =>
                  implicit val chargeFormat = DollarAmount.formatAsCents
                  val result = Json.obj(
                    "account" -> accountState,
                    "charge" -> chargeMaybe
                  )
                  Ok(result)
                }
              }
            case Failure(error) => Future.failed(error)
          }
        }
      case _ => Future.successful(OrganizationFail.INVALID_PUBLIC_ID.asErrorResponse)
    }
  }

  def getAvailablePlans(pubId: PublicId[Organization]) = OrganizationUserAction(pubId, OrganizationPermission.MANAGE_PLAN) { implicit request =>
    val (currentPlanId, availablePlans) = planCommander.getCurrentAndAvailablePlans(request.orgId)
    val sortedAvailablePlansByName = availablePlans.map(_.asInfo).groupBy(_.name).map { case (name, plans) => name -> plans.toSeq.sortBy(_.cycle.months) }
    val response = AvailablePlansResponse(PaidPlan.publicId(currentPlanId), sortedAvailablePlansByName)
    Ok(Json.toJson(response))
  }

  def updatePlan(pubId: PublicId[Organization], planPubId: PublicId[PaidPlan]) = OrganizationUserAction(pubId, OrganizationPermission.MANAGE_PLAN) { request =>
    PaidPlan.decodePublicId(planPubId) match {
      case Success(planId) =>
        if (planCommander.getActivePaymentMethods(request.orgId).nonEmpty) {
          val attribution = ActionAttribution(user = Some(request.request.userId), admin = request.request.adminUserId)
          planCommander.changePlan(request.orgId, planId, attribution) match {
            case Success(_) => Ok(Json.toJson(planCommander.currentPlan(request.orgId).asInfo))
            case Failure(ex) => BadRequest(Json.obj("error" -> ex.getMessage))
          }
        } else {
          BadRequest(Json.obj("error" -> "no_payment_method"))
        }
      case Failure(ex) => BadRequest(Json.obj("error" -> "invalid_plan_id"))
    }
  }

  def addCreditCard(pubId: PublicId[Organization]) = OrganizationUserAction(pubId, OrganizationPermission.MANAGE_PLAN).async(parse.tolerantJson) { request =>
    (request.body \ "token").asOpt[String] match {
      case None => Future.successful(BadRequest(Json.obj("error" -> "token_missing")))
      case Some(token) =>
        stripeClient.getPermanentToken(token, s"Card for Org ${request.orgId} added by user ${request.request.userId} with admin ${request.request.adminUserId}").flatMap { realToken =>
          stripeClient.getCardInfo(realToken).map { cardInfo =>
            val attribution = ActionAttribution(user = Some(request.request.userId), admin = request.request.adminUserId)
            val pm = planCommander.addPaymentMethod(request.orgId, realToken, attribution, cardInfo.lastFour)
            Ok(Json.obj("card" -> CardInfo(pm.id.get, cardInfo)))
          }
        }
    }
  }

  def setDefaultCreditCard(pubId: PublicId[Organization]) = OrganizationUserAction(pubId, OrganizationPermission.MANAGE_PLAN).async(parse.tolerantJson) { request =>
    (request.body \ "cardId").asOpt[PublicId[PaymentMethod]] match {
      case None => Future.successful(BadRequest(Json.obj("error" -> "default_card_missing")))
      case Some(pubCardId) => PaymentMethod.decodePublicId(pubCardId) match {
        case Failure(_) => Future.successful(OrganizationFail.INVALID_PUBLIC_ID.asErrorResponse)
        case Success(cardId) =>
          if (!planCommander.getActivePaymentMethods(request.orgId).flatMap(_.id).contains(cardId)) Future.successful(BadRequest(Json.obj("error" -> "invalid_card")))
          else {
            doSetDefaultCreditCardAndChargeMaybe(request.orgId, cardId, ActionAttribution(request.request.userIdOpt, None)).imap {
              case (card, charge) =>
                implicit val chargeFormat = DollarAmount.formatAsCents
                Ok(Json.obj(
                  "card" -> card,
                  "charge" -> charge
                ))
            } recover {
              case InvalidChange(msg) => BadRequest(Json.obj("error" -> msg))
            }
          }
      }
    }
  }

  private def doSetDefaultCreditCardAndChargeMaybe(orgId: Id[Organization], cardId: Id[PaymentMethod], attribution: ActionAttribution): Future[(CardInfo, Option[DollarAmount])] = {
    val card = planCommander.getPaymentMethod(cardId)
    stripeClient.getCardInfo(card.stripeToken).flatMap { cardInfo =>
      planCommander.changeDefaultPaymentMethod(orgId, cardId, attribution, cardInfo.lastFour) match {
        case Success((_, lastPaymentFailed)) =>
          val futureCharge = if (lastPaymentFailed) paymentCommander.processAccount(orgId).imap { case (_, event) => event.paymentCharge } else Future.successful(None)
          futureCharge.imap { charge => (CardInfo(cardId, cardInfo), charge) }
        case Failure(error) => Future.failed(error)
      }
    }
  }

  def getDefaultCreditCard(pubId: PublicId[Organization]) = OrganizationUserAction(pubId, OrganizationPermission.MANAGE_PLAN).async { request =>
    planCommander.getActivePaymentMethods(request.orgId).find(_.default).map { pm =>
      stripeClient.getCardInfo(pm.stripeToken).map { info =>
        Ok(Json.toJson(CardInfo(pm.id.get, info)))
      }
    }.getOrElse(Future.successful(BadRequest(Json.obj("error" -> "no_default_payment_method"))))
  }

  // deprecated
  def setCreditCardToken(pubId: PublicId[Organization]) = OrganizationUserAction(pubId, OrganizationPermission.MANAGE_PLAN).async(parse.tolerantJson) { request =>
    (request.body \ "token").asOpt[String] match {
      case None => Future.successful(BadRequest(Json.obj("error" -> "token_missing")))
      case Some(token) =>
        stripeClient.getPermanentToken(token, s"Card for Org ${request.orgId} added by user ${request.request.userId} with admin ${request.request.adminUserId}").flatMap { realToken =>
          stripeClient.getCardInfo(realToken).flatMap { cardInfo =>
            val attribution = ActionAttribution(user = Some(request.request.userId), admin = request.request.adminUserId)
            val pm = planCommander.addPaymentMethod(request.orgId, realToken, attribution, cardInfo.lastFour)
            planCommander.changeDefaultPaymentMethod(request.orgId, pm.id.get, attribution, cardInfo.lastFour) match {
              case Success((_, lastPaymentFailed)) =>
                val futureCharge = if (lastPaymentFailed) paymentCommander.processAccount(request.orgId).imap { case (_, event) => event.paymentCharge } else Future.successful(None)
                futureCharge.imap { charge =>
                  implicit val chargeFormat = DollarAmount.formatAsCents
                  Ok(Json.obj(
                    "card" -> CardInfo(pm.id.get, cardInfo),
                    "charge" -> charge
                  ))
                }
              case Failure(InvalidChange(msg)) => Future.successful(BadRequest(Json.obj("error" -> msg)))
              case Failure(ex) => Future.failed(ex)
            }
          }
        }
    }
  }

  def getEvents(pubId: PublicId[Organization], limit: Int, fromPubIdOpt: Option[String], inclusive: Boolean = false) = OrganizationUserAction(pubId, OrganizationPermission.MANAGE_PLAN) { request =>
    val fromIdOptTry = fromPubIdOpt.filter(_.nonEmpty) match {
      case None => Success(None)
      case Some(fromPubId) => AccountEvent.decodePublicId(PublicId(fromPubId)).map(Some(_))
    }
    fromIdOptTry match {
      case Success(fromIdOpt) =>
        val infos = activityLogCommander.getAccountEvents(request.orgId, fromIdOpt, Limit(limit), inclusive).map(activityLogCommander.buildSimpleEventInfo)
        Ok(Json.obj("events" -> infos))
      case Failure(ex) => BadRequest(Json.obj("error" -> "invalid_event_id"))
    }
  }

  def getReferralCode(pubId: PublicId[Organization]) = OrganizationUserAction(pubId, OrganizationPermission.REDEEM_CREDIT_CODE) { request =>
    Ok(Json.obj("code" -> creditRewardCommander.getOrCreateReferralCode(request.orgId)))
  }
  def redeemCreditCode(pubId: PublicId[Organization]) = OrganizationUserAction(pubId, OrganizationPermission.REDEEM_CREDIT_CODE)(parse.tolerantJson) { request =>
    val codeOpt = (request.body \ "code").asOpt[String].map(CreditCode.normalize)
    codeOpt match {
      case None => BadRequest(Json.obj("error" -> "missing_credit_code"))
      case Some(code) =>
        creditRewardCommander.applyCreditCode(CreditCodeApplyRequest(code, request.request.userId, Some(request.orgId))).map { rewards =>
          Ok(Json.obj("value" -> DollarAmount.formatAsCents.writes(rewards.target.credit)))
        }.recover {
          case f: CreditRewardFail => f.asErrorResponse
        }.get
    }
  }

  def getRewards(pubId: PublicId[Organization]) = OrganizationUserAction(pubId, OrganizationPermission.MANAGE_PLAN) { request =>
    Ok(Json.toJson(creditRewardInfoCommander.getRewardsByOrg(request.orgId)))
  }
}
