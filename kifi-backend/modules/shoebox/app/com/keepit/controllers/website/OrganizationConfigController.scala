package com.keepit.controllers.website

import com.google.inject.{ Inject, Singleton }
import com.keepit.commanders.{ PermissionCommander, OrganizationInfoCommander, OrganizationCommander }
import com.keepit.common.controller.{ ShoeboxServiceController, UserActions, UserActionsHelper }
import com.keepit.common.crypto.{ PublicIdConfiguration, PublicId }
import com.keepit.common.db.slick.Database
import com.keepit.model.ClassFeature.{ Blacklist, SlackIngestionDomainBlacklist }
import com.keepit.model._
import com.keepit.payments._
import com.keepit.shoebox.controllers.OrganizationAccessActions
import com.keepit.slack.SlackIngestingBlacklist
import play.api.libs.json.{ JsError, JsSuccess, Json }
import play.api.mvc.BodyParsers.parse

import scala.collection.mutable.ListBuffer
import scala.concurrent.ExecutionContext

@Singleton
class OrganizationConfigController @Inject() (
    orgCommander: OrganizationCommander,
    orgInfoCommander: OrganizationInfoCommander,
    planCommander: PlanManagementCommander,
    keepToLibraryRepo: KeepToLibraryRepo,
    keepRepo: KeepRepo,
    orgConfigRepo: OrganizationConfigurationRepo,
    val userActionsHelper: UserActionsHelper,
    val db: Database,
    val permissionCommander: PermissionCommander,
    implicit val publicIdConfig: PublicIdConfiguration,
    implicit val ec: ExecutionContext) extends UserActions with OrganizationAccessActions with ShoeboxServiceController {

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
    Ok(Json.toJson(orgInfoCommander.getExternalOrgConfiguration(request.orgId)))
  }

  def setAccountFeatureSettings(pubId: PublicId[Organization]) = OrganizationUserAction(pubId, OrganizationPermission.VIEW_SETTINGS)(parse.tolerantJson) { request =>
    request.body.validate[OrganizationSettings](OrganizationSettings.siteFormat) match {
      case JsError(errs) => BadRequest(Json.obj("error" -> "could_not_parse", "details" -> errs.toString))
      case JsSuccess(settings, _) =>
        val settingsRequest = OrganizationSettingsRequest(request.orgId, request.request.userId, settings)
        orgCommander.setAccountFeatureSettings(settingsRequest) match {
          case Left(fail) => fail.asErrorResponse
          case Right(response) =>
            val config = db.readOnlyMaster { implicit session => orgInfoCommander.getExternalOrgConfigurationHelper(request.orgId) } // avoiding using replica
            Ok(Json.toJson(config))
        }
    }
  }

  def backfillSlackBlacklist(pubId: PublicId[Organization]) = OrganizationUserAction(pubId, OrganizationPermission.VIEW_SETTINGS)(parse.tolerantJson) { request =>

    val readOnly = !(request.body \ "confirm").asOpt[Boolean].getOrElse(false)

    val blacklistedKeeps = db.readOnlyReplica { implicit session =>
      val blacklist = orgConfigRepo.getByOrgId(request.orgId).settings.settingFor(SlackIngestionDomainBlacklist).collect { case blk: Blacklist => blk.entries.map(_.path) }.getOrElse(Seq.empty)

      val batchSize = 1000
      var hasMore = true
      var pos = 0
      val blacklistedKeeps = ListBuffer[Keep]()

      do {
        val keepIds = keepToLibraryRepo.getByOrganizationId(request.orgId, drop = pos, take = batchSize).map(_.keepId)
        if (keepIds.nonEmpty) {
          val blacklisted = keepRepo.getByIds(keepIds.toSet).values.filter { keep =>
            SlackIngestingBlacklist.blacklistedUrl(keep.url, blacklist)
          }.toSeq
          blacklistedKeeps ++= blacklisted
        } else {
          hasMore = false
        }
        pos = pos + batchSize
      } while (hasMore)

      blacklistedKeeps
    }

    if (readOnly) {
      Ok(Json.obj("readonly" -> true, "keepCount" -> blacklistedKeeps.length))
    } else {
      // todo: Do keep deletion
      Ok(Json.obj("readonly" -> false, "keepCount" -> blacklistedKeeps.length))
    }
  }

}
