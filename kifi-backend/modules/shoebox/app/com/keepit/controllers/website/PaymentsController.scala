package com.keepit.controllers.website

import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.controller.{ UserActions, ShoeboxServiceController, UserActionsHelper }
import com.keepit.common.db.slick.Database
import com.keepit.shoebox.controllers.OrganizationAccessActions
import com.keepit.model._
import com.keepit.commanders.{ PermissionCommander, OrganizationCommander, OrganizationMembershipCommander, OrganizationInviteCommander }
import com.keepit.payments._

import play.api.libs.json.{ Json, JsSuccess, JsError }

import scala.util.{ Success, Failure }
import scala.concurrent.{ ExecutionContext, Future }

import com.google.inject.{ Inject, Singleton }

import org.joda.time.DateTime

@Singleton
class PaymentsController @Inject() (
    orgCommander: OrganizationCommander,
    orgMembershipCommander: OrganizationMembershipCommander,
    orgInviteCommander: OrganizationInviteCommander,
    paidPlanRepo: PaidPlanRepo,
    paidAccountRepo: PaidAccountRepo,
    planCommander: PlanManagementCommander,
    activityLogCommander: ActivityLogCommander,
    creditRewardCommander: CreditRewardCommander,
    stripeClient: StripeClient,
    val userActionsHelper: UserActionsHelper,
    val db: Database,
    val permissionCommander: PermissionCommander,
    implicit val publicIdConfig: PublicIdConfiguration,
    implicit val ec: ExecutionContext) extends UserActions with OrganizationAccessActions with ShoeboxServiceController {

  def getAccountState(pubId: PublicId[Organization]) = OrganizationUserAction(pubId, OrganizationPermission.MANAGE_PLAN).async { request =>
    planCommander.getAccountState(request.orgId).map { response => Ok(Json.toJson(response)) }
  }

  def getAvailablePlans(pubId: PublicId[Organization]) = OrganizationUserAction(pubId, OrganizationPermission.MANAGE_PLAN) { implicit request =>
    val (currentPlanId, availablePlans) = planCommander.getCurrentAndAvailablePlans(request.orgId)
    val sortedAvailablePlansByName = availablePlans.map(_.asInfo).groupBy(_.name).map { case (name, plans) => name -> plans.toSeq.sortBy(_.cycle.months) }
    val response = AvailablePlansResponse(PaidPlan.publicId(currentPlanId), sortedAvailablePlansByName)
    Ok(Json.toJson(response))
  }

  def getCreditCardToken(pubId: PublicId[Organization]) = OrganizationUserAction(pubId, OrganizationPermission.MANAGE_PLAN).async { request =>
    planCommander.getActivePaymentMethods(request.orgId).find(_.default).map { pm =>
      stripeClient.getCardInfo(pm.stripeToken).map { cardInfo =>
        Ok(Json.toJson(cardInfo))
      }
    }.getOrElse(Future.successful(BadRequest(Json.obj("error" -> "no_default_payment_method"))))
  }

  def setCreditCardToken(pubId: PublicId[Organization]) = OrganizationUserAction(pubId, OrganizationPermission.MANAGE_PLAN).async(parse.tolerantJson) { request =>
    (request.body \ "token").asOpt[String] match {
      case None => Future.successful(BadRequest(Json.obj("error" -> "token_missing")))
      case Some(token) =>
        stripeClient.getPermanentToken(token, s"Card for Org ${request.orgId} added by user ${request.request.userId} with admin ${request.request.adminUserId}").flatMap { realToken =>
          stripeClient.getCardInfo(realToken).map { cardInfo =>
            val attribution = ActionAttribution(user = Some(request.request.userId), admin = request.request.adminUserId)
            val pm = planCommander.addPaymentMethod(request.orgId, realToken, attribution, cardInfo.lastFour)
            planCommander.changeDefaultPaymentMethod(request.orgId, pm.id.get, attribution, cardInfo.lastFour)
            Ok(Json.toJson(cardInfo))
          }
        }
    }
  }

  def getAccountContacts(pubId: PublicId[Organization]) = OrganizationUserAction(pubId, OrganizationPermission.MANAGE_PLAN) { request =>
    Ok(Json.toJson(planCommander.getSimpleContactInfos(request.orgId)))
  }

  def setAccountContacts(pubId: PublicId[Organization]) = OrganizationUserAction(pubId, OrganizationPermission.MANAGE_PLAN)(parse.tolerantJson) { request =>
    request.body.validate[Seq[SimpleAccountContactSettingRequest]] match {
      case JsSuccess(contacts, _) => {
        val attribution = ActionAttribution(user = Some(request.request.userId), admin = request.request.adminUserId)
        contacts.foreach { contact =>
          planCommander.updateUserContact(request.orgId, contact.id, contact.enabled, attribution)
        }
        Ok(Json.toJson(planCommander.getSimpleContactInfos(request.orgId)))
      }
      case JsError(errs) => BadRequest(Json.obj("error" -> "could_not_parse", "details" -> errs.toString))
    }
  }

  def getAccountFeatureSettings(pubId: PublicId[Organization]) = OrganizationUserAction(pubId, OrganizationPermission.VIEW_SETTINGS) { request =>
    Ok(Json.toJson(orgCommander.getExternalOrgConfiguration(request.orgId)))
  }

  def setAccountFeatureSettings(pubId: PublicId[Organization]) = OrganizationUserAction(pubId, OrganizationPermission.VIEW_SETTINGS, OrganizationPermission.MANAGE_PLAN)(parse.tolerantJson) { request =>
    request.body.validate[OrganizationSettings](OrganizationSettings.siteFormat) match {
      case JsError(errs) => BadRequest(Json.obj("error" -> "could_not_parse", "details" -> errs.toString))
      case JsSuccess(settings, _) =>
        val settingsRequest = OrganizationSettingsRequest(request.orgId, request.request.userId, settings)
        orgCommander.setAccountFeatureSettings(settingsRequest) match {
          case Left(fail) => fail.asErrorResponse
          case Right(response) =>
            val plan = db.readOnlyMaster { implicit session => paidPlanRepo.get(paidAccountRepo.getByOrgId(request.orgId).planId) }
            val result = ExternalOrganizationConfiguration(plan.showUpsells, OrganizationSettingsWithEditability(response.config.settings, plan.editableFeatures))
            Ok(Json.toJson(result))
        }
    }
  }

  def updatePlan(pubId: PublicId[Organization], planPubId: PublicId[PaidPlan]) = OrganizationUserAction(pubId, OrganizationPermission.MANAGE_PLAN) { request =>
    PaidPlan.decodePublicId(planPubId) match {
      case Success(planId) =>
        val attribution = ActionAttribution(user = Some(request.request.userId), admin = request.request.adminUserId)
        planCommander.changePlan(request.orgId, planId, attribution) match {
          case Success(_) => Ok(Json.toJson(planCommander.currentPlan(request.orgId).asInfo))
          case Failure(ex) => BadRequest(Json.obj("error" -> ex.getMessage))
        }
      case Failure(ex) => BadRequest(Json.obj("error" -> "invalid_plan_id"))
    }
  }

  def getEvents(pubId: PublicId[Organization], limit: Int, fromPubIdOpt: Option[String]) = OrganizationUserAction(pubId, OrganizationPermission.MANAGE_PLAN) { request =>
    val fromIdOptTry = fromPubIdOpt.filter(_.nonEmpty) match {
      case None => Success(None)
      case Some(fromPubId) => AccountEvent.decodePublicId(PublicId(fromPubId)).map(Some(_))
    }
    fromIdOptTry match {
      case Success(fromIdOpt) =>
        val infos = activityLogCommander.getAccountEvents(request.orgId, fromIdOpt, Limit(limit)).map(activityLogCommander.buildSimpleEventInfo)
        Ok(Json.obj("events" -> infos))
      case Failure(ex) => BadRequest(Json.obj("error" -> "invalid_event_id"))
    }
  }

  def getReferralCode(pubId: PublicId[Organization]) = OrganizationUserAction(pubId, OrganizationPermission.REDEEM_CREDIT_CODE) { request =>
    Ok(Json.obj("code" -> creditRewardCommander.getOrCreateReferralCode(request.orgId)))
  }
  def redeemCreditCode(pubId: PublicId[Organization], code: String) = OrganizationUserAction(pubId, OrganizationPermission.REDEEM_CREDIT_CODE) { request =>
    creditRewardCommander.applyCreditCode(CreditCodeApplyRequest(CreditCode(code), request.request.userId, Some(request.orgId))).map { rewards =>
      Ok(Json.obj("value" -> rewards.target.credit))
    }.recover {
      case f: CreditRewardFail => f.asErrorResponse
    }.get
  }
}
