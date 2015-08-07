package com.keepit.controllers.website

import com.google.inject.Inject
import com.keepit.commanders._
import com.keepit.common.controller.{ ShoeboxServiceController, UserActions, UserActionsHelper }
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.store.{ SquareImageCropRegion, ImageCropRegion, ImageOffset, ImageSize }
import com.keepit.heimdal.HeimdalContextBuilderFactory
import com.keepit.model._
import com.keepit.shoebox.controllers.OrganizationAccessActions
import play.api.libs.json.Json

import scala.concurrent.ExecutionContext

class OrganizationAvatarController @Inject() (
  orgAvatarCommander: OrganizationAvatarCommander,
  heimdalContextBuilder: HeimdalContextBuilderFactory,
  airbrake: AirbrakeNotifier,
  val userActionsHelper: UserActionsHelper,
  val publicIdConfig: PublicIdConfiguration,
  val orgMembershipCommander: OrganizationMembershipCommander,
  implicit val config: PublicIdConfiguration,
  private implicit val executionContext: ExecutionContext)
    extends UserActions with OrganizationAccessActions with ShoeboxServiceController {

  def uploadAvatar(pubId: PublicId[Organization], x: Int, y: Int, s: Int) = OrganizationUserAction(pubId, OrganizationPermission.EDIT_ORGANIZATION).async(parse.temporaryFile) { request =>
    implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.site).build
    val cropRegion = SquareImageCropRegion(ImageOffset(x, y), s)
    val uploadImageF = orgAvatarCommander.persistOrganizationAvatarsFromUserUpload(request.orgId, request.body, cropRegion)
    uploadImageF.map {
      case Left(fail) => InternalServerError(Json.obj("error" -> fail.reason))
      case Right(hash) =>
        orgAvatarCommander.getBestImageByOrgId(request.orgId, OrganizationAvatarConfiguration.defaultSize) match {
          case Some(avatar: OrganizationAvatar) =>
            Ok(Json.obj("uploaded" -> avatar.imagePath))
          case None =>
            airbrake.notify(s"Uploaded an avatar for org ${request.orgId} but could not retrieve it", new Exception())
            NotFound(Json.obj("error" -> "image_not_found"))
        }
    }
  }
}

