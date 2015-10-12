package com.keepit.controllers.website

import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.controller.{ UserActions, ShoeboxServiceController, UserActionsHelper }
import com.keepit.common.db.ExternalId
import com.keepit.common.db.slick.Database
import com.keepit.shoebox.controllers.OrganizationAccessActions
import com.keepit.model._
import com.keepit.commanders.{ PermissionCommander, OrganizationCommander, OrganizationMembershipCommander, OrganizationInviteCommander }
import com.keepit.payments._

import com.kifi.macros.json

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
    stripeClient: StripeClient,
    val userActionsHelper: UserActionsHelper,
    val db: Database,
    val permissionCommander: PermissionCommander,
    implicit val publicIdConfig: PublicIdConfiguration,
    implicit val ec: ExecutionContext) extends UserActions with OrganizationAccessActions with ShoeboxServiceController {

  def getAccountState(pubId: PublicId[Organization]) = OrganizationUserAction(pubId, OrganizationPermission.MANAGE_PLAN).async { request =>
    import com.keepit.common.core._
    val lastFourFut = planCommander.getDefaultPaymentMethod(request.orgId).map { method =>
      stripeClient.getLastFourDigitsOfCard(method.stripeToken).map(Some(_))
    }.getOrElse(Future.successful(None))

    lastFourFut.map { lastFourOpt =>
      Ok(Json.obj(
        "credit" -> planCommander.getCurrentCredit(request.orgId).cents,
        "users" -> orgMembershipCommander.getMemberIds(request.orgId).size,
        "plan" -> planCommander.currentPlan(request.orgId).asInfo,
        "card" -> lastFourOpt
      ).nonNullFields)
    }
  }

  def getCreditCardToken(pubId: PublicId[Organization]) = OrganizationUserAction(pubId, OrganizationPermission.MANAGE_PLAN) { request =>
    planCommander.getActivePaymentMethods(request.orgId).find(_.default).map { pm =>
      Ok(Json.obj(
        "token" -> pm.stripeToken.token
      ))
    } getOrElse {
      Ok(Json.obj())
    }
  }

  def setCreditCardToken(pubId: PublicId[Organization]) = OrganizationUserAction(pubId, OrganizationPermission.MANAGE_PLAN).async(parse.tolerantJson) { request =>
    (request.body \ "token").asOpt[String] match {
      case Some(token) => {
        stripeClient.getPermanentToken(token, s"Card for Org ${request.orgId} added by user ${request.request.userId} with admin ${request.request.adminUserId}").flatMap { realToken =>
          stripeClient.getLastFourDigitsOfCard(realToken).map { lastFour =>
            val attribution = ActionAttribution(user = Some(request.request.userId), admin = request.request.adminUserId)
            val pm = planCommander.addPaymentMethod(request.orgId, realToken, attribution, lastFour)
            planCommander.changeDefaultPaymentMethod(request.orgId, pm.id.get, attribution, lastFour)
            Ok
          }
        }
      }
      case None => Future.successful(BadRequest(Json.obj("error" -> "token_missing")))
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

  def setAccountFeatureSettings(pubId: PublicId[Organization]) = OrganizationUserAction(pubId, OrganizationPermission.MANAGE_PLAN)(parse.tolerantJson) { request =>
    request.body.validate[OrganizationSettings](OrganizationSettings.siteFormat) match {
      case JsError(errs) => BadRequest(Json.obj("error" -> "could_not_parse", "details" -> errs.toString))
      case JsSuccess(settings, _) =>
        val settingsRequest = OrganizationSettingsRequest(request.orgId, request.request.userId, settings)
        orgCommander.setAccountFeatureSettings(settingsRequest) match {
          case Left(fail) => fail.asErrorResponse
          case Right(response) =>
            val plan = db.readOnlyMaster { implicit session => paidPlanRepo.get(paidAccountRepo.getByOrgId(request.orgId).planId) }
            val result = ExternalOrganizationConfiguration(
              plan.name.name,
              OrganizationSettingsWithEditability(response.config.settings, plan.editableFeatures)
            )
            Ok(Json.toJson(result))
        }
    }
  }

  def updatePlan(pubId: PublicId[Organization], planPubId: PublicId[PaidPlan]) = OrganizationUserAction(pubId, OrganizationPermission.MANAGE_PLAN) { request =>
    PaidPlan.decodePublicId(planPubId) match {
      case Success(planId) => {
        val attribution = ActionAttribution(user = Some(request.request.userId), admin = request.request.adminUserId)
        planCommander.changePlan(request.orgId, planId, attribution) match {
          case Success(_) => Ok(Json.toJson(planCommander.currentPlan(request.orgId).asInfo))
          case Failure(ex) => BadRequest(Json.obj("error" -> ex.getMessage))
        }

      }
      case Failure(ex) => BadRequest(Json.obj("error" -> "invalid_plan_id"))
    }
  }

  def getEvents(pubId: PublicId[Organization], limit: Int) = OrganizationUserAction(pubId, OrganizationPermission.MANAGE_PLAN) { request =>
    val infos = planCommander.getAccountEvents(request.orgId, limit, onlyRelatedToBillingFilter = None).map(planCommander.buildSimpleEventInfo)
    Ok(Json.obj("events" -> infos))
  }

  def getEventsBefore(pubId: PublicId[Organization], limit: Int, beforeTime: DateTime, beforePubId: PublicId[AccountEvent]) = OrganizationUserAction(pubId, OrganizationPermission.MANAGE_PLAN) { request =>
    AccountEvent.decodePublicId(beforePubId) match {
      case Success(beforeId) => {
        val infos = planCommander.getAccountEventsBefore(request.orgId, beforeTime, beforeId, limit, onlyRelatedToBillingFilter = None).map(planCommander.buildSimpleEventInfo)
        Ok(Json.obj("events" -> infos))
      }
      case Failure(ex) => BadRequest(Json.obj("error" -> "invalid_before_id"))
    }
  }
}
