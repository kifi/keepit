package com.keepit.controllers.client

import com.google.inject.Inject
import com.keepit.commanders.KeepCommander
import com.keepit.common.controller.{ ShoeboxServiceController, UserActions, UserActionsHelper }
import com.keepit.common.core.tryExtensionOps
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.time._
import com.keepit.common.util.RightBias
import com.keepit.common.util.RightBias.FromOption
import com.keepit.model._
import com.keepit.shoebox.data.assemblers.KeepInfoAssembler
import org.joda.time.DateTime
import play.api.libs.json.Json

import scala.concurrent.{ ExecutionContext, Future }

class KeepInfoController @Inject() (
  db: Database,
  val userActionsHelper: UserActionsHelper,
  keepRepo: KeepRepo,
  keepCommander: KeepCommander,
  keepInfoAssembler: KeepInfoAssembler,
  clock: Clock,
  implicit val airbrake: AirbrakeNotifier,
  private implicit val defaultContext: ExecutionContext,
  private implicit val publicIdConfig: PublicIdConfiguration)
    extends UserActions with ShoeboxServiceController {

  def getKeepView(pubId: PublicId[Keep]) = MaybeUserAction.async { implicit request =>
    val keepId = Keep.decodePublicId(pubId).get
    keepInfoAssembler.assembleKeepViews(request.userIdOpt, Set(keepId)).map { viewMap =>
      viewMap.getOrElse(keepId, RightBias.left(KeepFail.KEEP_NOT_FOUND)).fold(
        fail => fail.asErrorResponse,
        view => Ok(Json.toJson(view))
      )
    }
  }

  def getKeepStream(fromPubIdOpt: Option[String]) = UserAction.async { implicit request =>
    val goodResult = for {
      fromIdOpt <- fromPubIdOpt.filter(_.nonEmpty).map { pubId =>
        Keep.decodePublicIdStr(pubId).airbrakingOption.map(Option(_)).withLeft(KeepFail.INVALID_ID: KeepFail)
      }.getOrElse(RightBias.right(None))
    } yield {
      val keepIds = db.readOnlyMaster { implicit s =>
        val ugh = fromIdOpt.map(kId => keepRepo.get(kId).externalId) // I'm really sad about this external id right now :(
        keepRepo.getRecentKeepsByActivity(request.userId, limit = 10, beforeIdOpt = ugh, afterIdOpt = None, filterOpt = None).map(_._1.id.get)
      }
      keepInfoAssembler.assembleKeepViews(request.userIdOpt, keepSet = keepIds.toSet).map { viewMap =>
        Ok(Json.obj("keeps" -> keepIds.flatMap(kId => viewMap.get(kId).flatMap(_.getRight))))
      }
    }
    goodResult.getOrElse { fail => Future.successful(fail.asErrorResponse) }
  }

  def getActivityOnKeep(pubId: PublicId[Keep], limit: Int, fromTime: Option[DateTime]) = MaybeUserAction.async { implicit request =>
    val result = for {
      keepId <- Keep.decodePublicId(pubId).map(Future.successful).getOrElse(Future.failed(KeepFail.INVALID_ID))
      activity <- keepInfoAssembler.getActivityForKeep(keepId, fromTime, limit)
    } yield {
      Ok(Json.toJson(activity))
    }

    result.recover {
      case fail: KeepFail => fail.asErrorResponse
    }
  }
}
