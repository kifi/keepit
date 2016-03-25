package com.keepit.controllers.client

import com.google.inject.Inject
import com.keepit.commanders._
import com.keepit.common.controller.{ ShoeboxServiceController, UserActions, UserActionsHelper }
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.time._
import com.keepit.heimdal._
import com.keepit.model._
import com.keepit.shoebox.data.assemblers.KeepInfoAssembler
import play.api.libs.json.Json

import scala.concurrent.ExecutionContext

class ClientKeepController @Inject() (
  val userActionsHelper: UserActionsHelper,
  keepInfoAssembler: KeepInfoAssembler,
  clock: Clock,
  airbrake: AirbrakeNotifier,
  private implicit val defaultContext: ExecutionContext,
  private implicit val publicIdConfig: PublicIdConfiguration)
    extends UserActions with ShoeboxServiceController {

  def getNewKeepInfo(pubId: PublicId[Keep]) = MaybeUserAction.async { implicit request =>
    val keepId = Keep.decodePublicId(pubId).get
    keepInfoAssembler.assembleInfoForKeeps(request.userIdOpt, Seq(keepId)).map {
      case Seq((keepInfo, viewerInfo)) => Ok(Json.toJson(keepInfo))
    }
  }
}
