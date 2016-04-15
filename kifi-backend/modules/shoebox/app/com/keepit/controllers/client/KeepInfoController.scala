package com.keepit.controllers.client

import com.google.inject.{ Inject, Singleton }
import com.keepit.commanders._
import com.keepit.common.controller.{ ShoeboxServiceController, UserActions, UserActionsHelper }
import com.keepit.common.core.{ anyExtensionOps, tryExtensionOps }
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.SlackLog
import com.keepit.common.performance.Stopwatch
import com.keepit.common.time._
import com.keepit.common.util.{ TimedComputation, RightBias }
import com.keepit.common.util.RightBias.FromOption
import com.keepit.model._
import com.keepit.shoebox.data.assemblers.{ KeepActivityAssembler, KeepInfoAssembler }
import com.keepit.slack.{ InhouseSlackClient, InhouseSlackChannel }
import com.kifi.macros.json
import org.apache.commons.lang3.RandomStringUtils
import org.joda.time.DateTime
import play.api.libs.json.{ JsObject, Json }

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
  typeaheadCommander: TypeaheadCommander,
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

  def getKeepStream(fromPubIdOpt: Option[String], limit: Int) = UserAction.async { implicit request =>
    val stopwatch = new Stopwatch(s"[KIC-STREAM-${RandomStringUtils.randomAlphanumeric(5)}]")
    val goodResult = for {
      _ <- RightBias.unit.filter(_ => limit < 100, KeepFail.LIMIT_TOO_LARGE: KeepFail)
      fromIdOpt <- fromPubIdOpt.filter(_.nonEmpty).fold[RightBias[KeepFail, Option[Id[Keep]]]](RightBias.right(None)) { pubId =>
        Keep.decodePublicIdStr(pubId).airbrakingOption.withLeft(KeepFail.INVALID_KEEP_ID: KeepFail).map(Some(_))
      }
    } yield {
      stopwatch.logTimeWith("input_decoded")
      val keepIds = db.readOnlyMaster { implicit s =>
        val ugh = fromIdOpt.map(kId => keepRepo.get(kId).externalId) // I'm really sad about this external id right now :(
        keepRepo.getRecentKeepsByActivity(request.userId, limit = limit, beforeIdOpt = ugh, afterIdOpt = None, filterOpt = None).map(_._1.id.get)
      }
      stopwatch.logTimeWith(s"query_complete_n_${keepIds.length}")
      keepInfoAssembler.assembleKeepViews(request.userIdOpt, keepSet = keepIds.toSet).map { viewMap =>
        stopwatch.logTimeWith("done")
        Ok(Json.obj("keeps" -> keepIds.flatMap(kId => viewMap.get(kId).flatMap(_.getRight))))
      }
    }
    goodResult.getOrElse { fail => Future.successful(fail.asErrorResponse) }
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

  @json case class RecipientSuggestion(query: Option[String], results: Seq[JsObject /* TypeaheadResult */ ], mayHaveMore: Boolean, limit: Option[Int], offset: Option[Int])
  def suggestRecipient(query: Option[String], limit: Option[Int], offset: Option[Int], requested: Option[String]) = UserAction.async { request =>
    val requestedSet = requested.map(_.split(",").map(_.trim).flatMap(TypeaheadRequest.applyOpt).toSet).filter(_.nonEmpty).getOrElse(TypeaheadRequest.all)
    typeaheadCommander.searchForKeepRecipients(request.userId, query.getOrElse(""), limit, offset, requestedSet).map { suggestions =>
      val body = suggestions.take(limit.getOrElse(20)).collect {
        case u: UserContactResult => Json.toJson(u).as[JsObject] ++ Json.obj("kind" -> "user")
        case e: EmailContactResult => Json.toJson(e).as[JsObject] ++ Json.obj("kind" -> "email")
        case l: LibraryResult => Json.toJson(l).as[JsObject] ++ Json.obj("kind" -> "library")
      }
      Ok(Json.toJson(RecipientSuggestion(query.map(_.trim).filter(_.nonEmpty), body, suggestions.nonEmpty, limit, offset)))
    }
  }

}
