package com.keepit.controllers.website

import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.controller.{ UserActions, ShoeboxServiceController, UserActionsHelper }
import com.keepit.common.db.ExternalId
import com.keepit.shoebox.controllers.OrganizationAccessActions
import com.keepit.model.{ Organization, OrganizationPermission, User }
import com.keepit.commanders.{ OrganizationCommander, OrganizationMembershipCommander, OrganizationInviteCommander }
import com.keepit.payments._

import com.kifi.macros.json

import play.api.libs.json.{ Json, JsSuccess, JsError }

import scala.util.{ Try, Success, Failure }

import com.google.inject.{ Inject, Singleton }

import org.joda.time.DateTime

@Singleton
class PaymentsController @Inject() (
    val orgCommander: OrganizationCommander,
    val orgMembershipCommander: OrganizationMembershipCommander,
    val orgInviteCommander: OrganizationInviteCommander,
    val userActionsHelper: UserActionsHelper,
    planCommander: PlanManagementCommander,
    implicit val publicIdConfig: PublicIdConfiguration) extends UserActions with OrganizationAccessActions with ShoeboxServiceController {

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

  def setCreditCardToken(pubId: PublicId[Organization]) = OrganizationUserAction(pubId, PLAN_MANAGEMENT_PERMISSION)(parse.tolerantJson) { request =>
    (request.body \ "token").asOpt[String] match {
      case Some(token) => { //ZZZ TODO: Need to exchange this one time token for a permanent one with stripe
        val attribution = ActionAttribution(user = Some(request.request.userId), admin = request.request.adminUserId)
        val pm = planCommander.addPaymentMethod(request.orgId, StripeToken(token), attribution)
        planCommander.changeDefaultPaymentMethod(request.orgId, pm.id.get, attribution)
        Ok
      }
      case None => BadRequest("token_missing")
    }
  }

  def getAccountContacts(pubId: PublicId[Organization]) = OrganizationUserAction(pubId, PLAN_MANAGEMENT_PERMISSION) { request =>
    Ok(Json.toJson(planCommander.getSimpleContactInfos(request.orgId)))
  }

  @json
  case class SimpleAccountContactSettingRequest(id: ExternalId[User], enabled: Boolean)

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

  def getPlanFeatureSettings(pubId: PublicId[Organization]) = OrganizationUserAction(pubId, PLAN_MANAGEMENT_PERMISSION) { request => //ZZZ TODO: This is currently a dummy
    val dummyFeatureOne = PlanFeature(name = "keeping", displayName = "Users can keep things", editable = false, default = true)
    val dummyFeatureTwo = PlanFeature(name = "messaging", displayName = "Users can send messages", editable = true, default = true)
    val settings = Seq(
      PlanFeatureSetting(dummyFeatureOne, enabled = true),
      PlanFeatureSetting(dummyFeatureTwo, enabled = false)
    )
    Ok(Json.obj("settings" -> settings))
  }

  @json
  case class SimplePlanFeaureSettingRequest(name: String, enabled: Boolean)

  def setPlanFeatureSettings(pubId: PublicId[Organization]) = OrganizationUserAction(pubId, PLAN_MANAGEMENT_PERMISSION)(parse.tolerantJson) { request => //ZZZ TODO: This is currently a dummy (just does request format validation)
    request.body.validate[Seq[SimplePlanFeaureSettingRequest]] match {
      case JsSuccess(settings, _) => Ok
      case JsError(errs) => BadRequest(Json.obj("error" -> "could_not_parse", "details" -> errs.toString))
    }
  }

  def updatePlan(pubId: PublicId[Organization], planPubId: PublicId[PaidPlan]) = OrganizationUserAction(pubId, PLAN_MANAGEMENT_PERMISSION) { request => //ZZZ TODO: depending on what we decide to do with people who downgrade their plan this might have to do a lot more
    PaidPlan.decodePublicId(planPubId) match {
      case Success(planId) => {
        val attribution = ActionAttribution(user = Some(request.request.userId), admin = request.request.adminUserId)
        planCommander.changePlan(request.orgId, planId, attribution) match {
          case Success(_) => Ok(Json.toJson(planCommander.currentPlan(request.orgId).asInfo))
          case Failure(ex) => BadRequest(ex.getMessage)
        }

      }
      case Failure(ex) => BadRequest("invalid_plan_id")
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
      case Failure(ex) => BadRequest("invalid_before_id")
    }

  }
}
