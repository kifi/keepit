package com.keepit.controllers.client

import com.google.inject.{ Inject, Singleton }
import com.keepit.commanders.KeepCommander
import com.keepit.common.controller.{ ShoeboxServiceController, UserActions, UserActionsHelper }
import com.keepit.common.core.{ anyExtensionOps, tryExtensionOps }
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.SlackLog
import com.keepit.common.time._
import com.keepit.common.util.{ TimedComputation, RightBias }
import com.keepit.common.util.RightBias.FromOption
import com.keepit.model._
import com.keepit.shoebox.data.assemblers.{ KeepActivityAssembler, KeepInfoAssembler }
import com.keepit.slack.{ InhouseSlackClient, InhouseSlackChannel }
import org.joda.time.DateTime
import play.api.libs.json.Json

import scala.concurrent.{ ExecutionContext, Future }

@Singleton
class KeepInfoController @Inject() (
  db: Database,
  val userActionsHelper: UserActionsHelper,
  keepRepo: KeepRepo,
  keepCommander: KeepCommander,
  keepInfoAssembler: KeepInfoAssembler,
  keepActivityAssembler: KeepActivityAssembler,
  clock: Clock,
  implicit val airbrake: AirbrakeNotifier,
  private implicit val defaultContext: ExecutionContext,
  private implicit val publicIdConfig: PublicIdConfiguration,
  private implicit val inhouseSlackClient: InhouseSlackClient)
    extends UserActions with ShoeboxServiceController {

  private val ryanLog = new SlackLog(InhouseSlackChannel.TEST_RYAN)
  private val ryan = Id[User](84792)

  def getKeepView(pubId: PublicId[Keep]) = MaybeUserAction.async { implicit request =>
    val keepId = Keep.decodePublicId(pubId).get
    keepInfoAssembler.assembleKeepViews(request.userIdOpt, Set(keepId)).map { viewMap =>
      viewMap.getOrElse(keepId, RightBias.left(KeepFail.KEEP_NOT_FOUND)).fold(
        fail => fail.asErrorResponse,
        view => Ok(Json.toJson(view))
      )
    }
  }

  def getKeepStream(fromPubIdOpt: Option[String], limit: Int) = {
    val start = System.currentTimeMillis()
    UserAction.async { implicit request =>
      TimedComputation.async {
        val goodResult = for {
          _ <- RightBias.unit.filter(_ => limit < 100, KeepFail.LIMIT_TOO_LARGE: KeepFail)
          fromIdOpt <- fromPubIdOpt.filter(_.nonEmpty).fold[RightBias[KeepFail, Option[Id[Keep]]]](RightBias.right(None)) { pubId =>
            Keep.decodePublicIdStr(pubId).airbrakingOption.withLeft(KeepFail.INVALID_KEEP_ID: KeepFail).map(Some(_))
          }
        } yield {
          val keepIds = TimedComputation.sync {
            db.readOnlyMaster { implicit s =>
              val ugh = fromIdOpt.map(kId => keepRepo.get(kId).externalId) // I'm really sad about this external id right now :(
              keepRepo.getRecentKeepsByActivity(request.userId, limit = limit, beforeIdOpt = ugh, afterIdOpt = None, filterOpt = None).map(_._1.id.get)
            }
          } |> { tc =>
            if (request.userId == ryan) ryanLog.info("Retrieving the keep ids took", tc.millis, tc.range.toString())
            tc.value
          }
          TimedComputation.async(keepInfoAssembler.assembleKeepViews(request.userIdOpt, keepSet = keepIds.toSet)).map { tc =>
            if (request.userId == ryan) ryanLog.info("Generating keep views took", tc.millis, tc.range.toString())
            val viewMap = tc.value
            Ok(Json.obj("keeps" -> keepIds.flatMap(kId => viewMap.get(kId).flatMap(_.getRight))))
          }
        }
        goodResult.getOrElse { fail => Future.successful(fail.asErrorResponse) }
      }.map { tc =>
        if (request.userId == ryan) ryanLog.info("The whole request took", tc.millis, tc.range.toString(), "and the method was invoked at", start)
        tc.value
      }
    }
  }

  def getActivityOnKeep(pubId: PublicId[Keep], limit: Int, fromTime: Option[DateTime]) = MaybeUserAction.async { implicit request =>
    val result = for {
      keepId <- Keep.decodePublicId(pubId).map(Future.successful).getOrElse(Future.failed(KeepFail.INVALID_KEEP_ID))
      activity <- keepActivityAssembler.getActivityForKeep(keepId, fromTime, limit)
    } yield {
      Ok(Json.toJson(activity))
    }

    result.recover {
      case fail: KeepFail => fail.asErrorResponse
    }
  }
}
