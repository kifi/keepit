package com.keepit.controllers.website

import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.controller.{ UserActions, ShoeboxServiceController, UserActionsHelper }
import com.keepit.common.db.ExternalId
import com.keepit.shoebox.controllers.OrganizationAccessActions
import com.keepit.model.{ OrganizationPermission, Organization }
import com.keepit.commanders.{ OrganizationCommander, OrganizationMembershipCommander, OrganizationInviteCommander }
import com.keepit.payments._

import com.kifi.macros.json

import play.api.libs.json.{ Json, JsSuccess, JsError }

import scala.util.{ Try, Success, Failure }
import scala.concurrent.{ ExecutionContext, Future }

import com.google.inject.{ Inject, Singleton }

import org.joda.time.DateTime

@Singleton
class PaymentsController @Inject() (
    val orgCommander: OrganizationCommander,
    val orgMembershipCommander: OrganizationMembershipCommander,
    val orgInviteCommander: OrganizationInviteCommander,
    val userActionsHelper: UserActionsHelper,
    planCommander: PlanManagementCommander,
    stripeClient: StripeClient,
    implicit val publicIdConfig: PublicIdConfiguration,
    implicit val ec: ExecutionContext) extends UserActions with OrganizationAccessActions with ShoeboxServiceController {

  private val PLAN_MANAGEMENT_PERMISSION = OrganizationPermission.EDIT_ORGANIZATION

  def getAccountState(pubId: PublicId[Organization]) = OrganizationUserAction(pubId, PLAN_MANAGEMENT_PERMISSION) { request =>
    Ok(Json.obj(
      "credit" -> planCommander.getCurrentCredit(request.orgId).cents,
      "users" -> orgMembershipCommander.getMemberIds(request.orgId).size,
      "plan" -> planCommander.currentPlan(request.orgId).asInfo
    ))
  }

  def getCreditCardToken(pubId: PublicId[Organization]) = OrganizationUserAction(pubId, PLAN_MANAGEMENT_PERMISSION) { request =>
    planCommander.getActivePaymentMethods(request.orgId).filter(_.default).headOption.map { pm =>
      Ok(Json.obj(
        "token" -> pm.stripeToken.token
      ))
    } getOrElse {
      Ok(Json.obj())
    }
  }

  def setCreditCardToken(pubId: PublicId[Organization]) = OrganizationUserAction(pubId, PLAN_MANAGEMENT_PERMISSION).async(parse.tolerantJson) { request =>
    (request.body \ "token").asOpt[String] match {
      case Some(token) => {
        stripeClient.getPermanentToken(token, s"Card for Org ${request.orgId} added by user ${request.request.userId} with admin ${request.request.adminUserId}").map { realToken =>
          val attribution = ActionAttribution(user = Some(request.request.userId), admin = request.request.adminUserId)
          val pm = planCommander.addPaymentMethod(request.orgId, realToken, attribution)
          planCommander.changeDefaultPaymentMethod(request.orgId, pm.id.get, attribution)
          Ok
        }
      }
      case None => Future.successful(BadRequest(Json.obj("error" -> "token_missing")))
    }
  }

  def getAccountContacts(pubId: PublicId[Organization]) = OrganizationUserAction(pubId, PLAN_MANAGEMENT_PERMISSION) { request =>
    Ok(Json.toJson(planCommander.getSimpleContactInfos(request.orgId)))
  }

  def setAccountContacts(pubId: PublicId[Organization]) = OrganizationUserAction(pubId, PLAN_MANAGEMENT_PERMISSION)(parse.tolerantJson) { request =>
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

  def getAccountFeatureSettings(pubId: PublicId[Organization]) = OrganizationUserAction(pubId, PLAN_MANAGEMENT_PERMISSION) { request =>
    val accountFeatureSettingsResponse = planCommander.getAccountFeatureSettings(request.orgId)
    Ok(Json.toJson(accountFeatureSettingsResponse))
  }

  def setAccountFeatureSettings(pubId: PublicId[Organization]) = OrganizationUserAction(pubId, PLAN_MANAGEMENT_PERMISSION)(parse.tolerantJson) { request =>
    request.body.validate[SimpleAccountFeatureSettingRequest] match {
      case JsError(errs) => BadRequest(Json.obj("error" -> "could_not_parse", "details" -> errs.toString))
      case JsSuccess(settings, _) =>
        val response = planCommander.setAccountFeatureSettings(request.orgId, request.request.userId, settings.featureSettings)
        Ok(Json.toJson(response))
    }
  }

  def updatePlan(pubId: PublicId[Organization], planPubId: PublicId[PaidPlan]) = OrganizationUserAction(pubId, PLAN_MANAGEMENT_PERMISSION) { request => //ZZZ TODO: depending on what we decide to do with people who downgrade their plan this might have to do a lot more
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

  def getEvents(pubId: PublicId[Organization], limit: Int) = OrganizationUserAction(pubId, PLAN_MANAGEMENT_PERMISSION) { request =>
    val infos = planCommander.getAccountEvents(request.orgId, limit, onlyRelatedToBillingFilter = None).map(planCommander.buildSimpleEventInfo)
    Ok(Json.obj("events" -> infos))
  }

  def getEventsBefore(pubId: PublicId[Organization], limit: Int, beforeTime: DateTime, beforePubId: PublicId[AccountEvent]) = OrganizationUserAction(pubId, PLAN_MANAGEMENT_PERMISSION) { request =>
    AccountEvent.decodePublicId(beforePubId) match {
      case Success(beforeId) => {
        val infos = planCommander.getAccountEventsBefore(request.orgId, beforeTime, beforeId, limit, onlyRelatedToBillingFilter = None).map(planCommander.buildSimpleEventInfo)
        Ok(Json.obj("events" -> infos))
      }
      case Failure(ex) => BadRequest(Json.obj("error" -> "invalid_before_id"))
    }

  }
}
