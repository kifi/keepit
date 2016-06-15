package com.keepit.controllers.client

import com.google.inject.{ Inject, Singleton }
import com.keepit.commanders._
import com.keepit.common.controller.{ ShoeboxServiceController, UserActions, UserActionsHelper }
import com.keepit.common.core.{ anyExtensionOps, tryExtensionOps }
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.json
import com.keepit.common.json.TupleFormat
import com.keepit.common.logging.SlackLog
import com.keepit.common.net.QsValue
import com.keepit.common.performance.Stopwatch
import com.keepit.common.time._
import com.keepit.common.util.{ TimedComputation, RightBias }
import com.keepit.common.util.RightBias.FromOption
import com.keepit.model.UserValues.UserValueBooleanHandler
import com.keepit.model._
import com.keepit.shoebox.data.assemblers.KeepInfoAssemblerConfig.KeepViewAssemblyOptions
import com.keepit.shoebox.data.assemblers.{ KeepInfoAssemblerConfig, KeepActivityAssembler, KeepInfoAssembler }
import com.keepit.slack.{ InhouseSlackClient, InhouseSlackChannel }
import com.kifi.macros.json
import org.apache.commons.lang3.RandomStringUtils
import org.joda.time.DateTime
import play.api.libs.json.{ JsArray, JsObject, Json }
import com.keepit.common.core._

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
  userValueRepo: UserValueRepo,
  permissionCommander: PermissionCommander,
  implicit val airbrake: AirbrakeNotifier,
  private implicit val defaultContext: ExecutionContext,
  private implicit val publicIdConfig: PublicIdConfiguration)
    extends UserActions with ShoeboxServiceController {

  def getKeepView(pubId: PublicId[Keep], config: KeepViewAssemblyOptions) = MaybeUserAction.async { implicit request =>
    val keepId = Keep.decodePublicId(pubId).get
    keepInfoAssembler.assembleKeepViews(request.userIdOpt, Set(keepId), config = config).map { viewMap =>
      viewMap.getOrElse(keepId, RightBias.left(KeepFail.KEEP_NOT_FOUND)).fold(
        fail => fail.asErrorResponse,
        view => Ok(Json.toJson(view))
      )
    }
  }

  def getKeepStream(fromPubIdOpt: Option[String], limit: Option[Int], config: KeepViewAssemblyOptions) = UserAction.async { implicit request =>
    val stopwatch = new Stopwatch(s"[KIC-STREAM-${RandomStringUtils.randomAlphanumeric(5)}]")
    val goodResult = for {
      _ <- RightBias.unit.filter(_ => limit.forall(_ <= 50), KeepFail.LIMIT_TOO_LARGE: KeepFail)
      fromIdOpt <- fromPubIdOpt.filter(_.nonEmpty).fold[RightBias[KeepFail, Option[Id[Keep]]]](RightBias.right(None)) { pubId =>
        Keep.decodePublicIdStr(pubId).airbrakingOption.withLeft(KeepFail.INVALID_KEEP_ID).map(Some(_))
      }
    } yield {
      stopwatch.logTimeWith("input_decoded")
      val keepIds = db.readOnlyMaster { implicit s =>
        val numKeepsToShow = {
          limit.getOrElse {
            val usesCompactCards = userValueRepo.getValue(request.userId, UserValueBooleanHandler(UserValueName.USE_MINIMAL_KEEP_CARD, default = false))
            if (usesCompactCards) 6 else 3
          }
        }
        val ugh = fromIdOpt.map(kId => keepRepo.get(kId).externalId) // I'm really sad about this external id right now :(
        keepRepo.getRecentKeepsByActivity(request.userId, limit = numKeepsToShow, beforeIdOpt = ugh, afterIdOpt = None, filterOpt = None).map(_._1.id.get)
      }
      stopwatch.logTimeWith(s"query_complete_n_${keepIds.length}")
      keepInfoAssembler.assembleKeepViews(request.userIdOpt, keepSet = keepIds.toSet, config = config).map { viewMap =>
        stopwatch.logTimeWith("done")
        Ok(Json.obj("keeps" -> keepIds.flatMap(kId => viewMap.get(kId).flatMap(_.getRight))))
      }
    }
    goodResult.getOrElse { fail => Future.successful(fail.asErrorResponse) }
  }

  def getActivityOnKeep(pubId: PublicId[Keep], limit: Int, fromTime: Option[DateTime]) = MaybeUserAction.async { implicit request =>
    val result = for {
      keepId <- Keep.decodePublicId(pubId).map(Future.successful).getOrElse(Future.failed(KeepFail.INVALID_KEEP_ID))
      _ <- {
        val permissions = db.readOnlyMaster { implicit s =>
          permissionCommander.getKeepPermissions(keepId, request.userIdOpt)
        }
        if (permissions.contains(KeepPermission.VIEW_KEEP)) Future.successful(())
        else Future.failed(KeepFail.INSUFFICIENT_PERMISSIONS)
      }
      activity <- keepActivityAssembler.getActivityForKeep(keepId, fromTime, limit)
    } yield {
      Ok(Json.toJson(activity))
    }

    result.recover {
      case fail: KeepFail => fail.asErrorResponse
    }
  }

  @json case class RecipientSuggestion(query: Option[String], results: Seq[JsObject /* TypeaheadResult */ ], mayHaveMore: Boolean, limit: Option[Int], offset: Option[Int])
  def suggestRecipient(keepIdStr: Option[String], query: Option[String], limit: Option[Int], offset: Option[Int], requested: Option[String]) = UserAction.async { request =>
    val keepId = keepIdStr.map(Keep.decodePublicIdStr(_).get)
    val requestedSet = requested.map(_.split(',').map(_.trim).flatMap(TypeaheadRequest.applyOpt).toSet).filter(_.nonEmpty).getOrElse(TypeaheadRequest.all)
    keepCommander.suggestRecipients(request.userId, keepId, query, offset getOrElse 0, limit getOrElse 20, requestedSet).map { suggestions =>
      val body = suggestions.map {
        case u: UserContactResult => Json.toJson(u).as[JsObject] ++ Json.obj("kind" -> "user")
        case e: EmailContactResult => Json.toJson(e).as[JsObject] ++ Json.obj("kind" -> "email")
        case l: LibraryResult => Json.toJson(l).as[JsObject] ++ Json.obj("kind" -> "library")
      }
      Ok(Json.toJson(RecipientSuggestion(query.map(_.trim).filter(_.nonEmpty), body, suggestions.nonEmpty, limit, offset)))
    }
  }

  def suggestTags(keepIdStr: Option[String], query: Option[String], limit: Option[Int]) = UserAction.async { request =>
    val keepId = keepIdStr.flatMap(Keep.decodePublicIdStr(_).toOption)
    keepCommander.suggestTags(request.userId, keepId, query, limit.getOrElse(10)).imap { tagsAndMatches =>
      implicit val matchesWrites = TupleFormat.tuple2Writes[Int, Int]
      val result = JsArray(tagsAndMatches.map { case (tag, matches) => json.aggressiveMinify(Json.obj("tag" -> tag, "matches" -> matches)) })
      Ok(result)
    }
  }

}
