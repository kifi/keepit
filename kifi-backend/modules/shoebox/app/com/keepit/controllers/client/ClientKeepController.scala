package com.keepit.controllers.client

import com.google.inject.Inject
import com.keepit.commanders._
import com.keepit.common.core.tryExtensionOps
import com.keepit.common.controller.{ ShoeboxServiceController, UserActions, UserActionsHelper }
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.time._
import com.keepit.common.util.RightBias
import com.keepit.common.util.RightBias.FromOption
import com.keepit.heimdal._
import com.keepit.model._
import com.keepit.shoebox.data.assemblers.KeepInfoAssembler
import play.api.libs.json.Json

import scala.concurrent.{ Future, ExecutionContext }

class ClientKeepController @Inject() (
  db: Database,
  val userActionsHelper: UserActionsHelper,
  keepRepo: KeepRepo,
  keepInfoAssembler: KeepInfoAssembler,
  clock: Clock,
  implicit val airbrake: AirbrakeNotifier,
  private implicit val defaultContext: ExecutionContext,
  private implicit val publicIdConfig: PublicIdConfiguration)
    extends UserActions with ShoeboxServiceController {

  def getNewKeepView(pubId: PublicId[Keep]) = MaybeUserAction.async { implicit request =>
    val keepId = Keep.decodePublicId(pubId).get
    keepInfoAssembler.assembleKeepViews(request.userIdOpt, Set(keepId)).map { viewMap =>
      Ok(Json.toJson(viewMap(keepId)))
    }
  }

  def getKeepStream(fromPubIdOpt: Option[String]) = UserAction.async { implicit request =>
    val goodResult = for {
      fromIdOpt <- fromPubIdOpt.filter(_.nonEmpty).map { pubId =>
        Keep.decodePublicIdStr(pubId).airbrakingOption.map(Option(_)).withLeft(KeepFail.INVALID_ID: KeepFail)
      }.getOrElse(RightBias.right(None))
    } yield {
      val keepIds = db.readOnlyMaster { implicit s =>
        val ugh = fromIdOpt.map(kId => keepRepo.get(kId).externalId)
        keepRepo.getRecentKeepsByActivity(request.userId, limit = 10, beforeIdOpt = ugh, afterIdOpt = None, filterOpt = None).map(_._1.id.get)
      }
      keepInfoAssembler.assembleKeepViews(request.userIdOpt, keepSet = keepIds.toSet).map { viewMap =>
        Ok(Json.obj("keeps" -> keepIds.flatMap(viewMap.get)))
      }
    }
    goodResult.getOrElse { fail => Future.successful(fail.asErrorResponse) }
  }
}
