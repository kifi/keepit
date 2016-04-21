package com.keepit.controllers.mobile

import com.keepit.commanders._
import com.keepit.common.json
import com.keepit.common.net.{ UserAgent, URISanitizer }
import com.keepit.common.util.RightBias
import com.keepit.common.util.RightBias._
import com.keepit.heimdal._
import com.keepit.common.controller.{ UserRequest, ShoeboxServiceController, UserActions, UserActionsHelper }
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.model._
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.shoebox.data.keep.{ PartialKeepInfo, KeepInfo }

import play.api.libs.json._

import com.keepit.common.akka.SafeFuture
import com.google.inject.Inject
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.concurrent.Future
import com.keepit.common.store.ImageSize
import com.keepit.normalizer.NormalizedURIInterner
import com.keepit.common.core._
import com.keepit.common.json.TupleFormat

import scala.util.Try

class MobileKeepsController @Inject() (
  db: Database,
  keepRepo: KeepRepo,
  val userActionsHelper: UserActionsHelper,
  keepDecorator: KeepDecorator,
  keepsCommander: KeepCommander,
  tagCommander: TagCommander,
  normalizedURIInterner: NormalizedURIInterner,
  libraryInfoCommander: LibraryInfoCommander,
  rawBookmarkFactory: RawBookmarkFactory,
  heimdalContextBuilder: HeimdalContextBuilderFactory,
  implicit val publicIdConfig: PublicIdConfiguration)
    extends UserActions with ShoeboxServiceController {

  def allKeepsV2(before: Option[String], after: Option[String], collectionOpt: Option[String], helprankOpt: Option[String], count: Int, withPageInfo: Boolean) = UserAction.async { request =>
    getAllKeeps(request.userId, before, after, collectionOpt, helprankOpt, count, withPageInfo, false)
  }

  private def getAllKeeps(userId: Id[User], before: Option[String], after: Option[String], collectionOpt: Option[String], helprankOpt: Option[String], count: Int, withPageInfo: Boolean, v1: Boolean) = {
    keepsCommander.allKeeps(before map ExternalId[Keep], after map ExternalId[Keep], collectionOpt, helprankOpt, count, userId) map { res =>
      val helprank = helprankOpt map (selector => Json.obj("helprank" -> selector)) getOrElse Json.obj()
      val sanitizedKeeps = res.map(k => k.copy(url = URISanitizer.sanitize(k.url), note = Hashtags.formatMobileNote(k.note, v1)))
      Ok(Json.obj(
        "before" -> before,
        "after" -> after,
        "keeps" -> Json.toJson(sanitizedKeeps)
      ) ++ helprank)
    }
  }

  def allCollections(sort: String) = UserAction.async { request =>
    for {
      numKeeps <- SafeFuture { db.readOnlyMaster { implicit s => keepRepo.getCountByUser(request.userId) } }
      collections <- SafeFuture { tagCommander.tagsForUser(request.userId, 0, 1000, TagSorting(sort)) }
    } yield {
      Ok(Json.obj(
        "keeps" -> numKeeps,
        "collections" -> collections
      ))
    }
  }

  def suggestTags(keepIdOptStringHack: Option[String], query: Option[String], limit: Int) = UserAction.async { request =>
    val keepIdOpt = keepIdOptStringHack.flatMap(ExternalId.asOpt[Keep]).flatMap { extId =>
      db.readOnlyReplica { implicit s => keepRepo.getOpt(extId).flatMap(_.id) }
    }
    keepsCommander.suggestTags(request.userId, keepIdOpt, query, limit).imap { tagsAndMatches =>
      implicit val matchesWrites = TupleFormat.tuple2Writes[Int, Int]
      val result = JsArray(tagsAndMatches.map { case (tag, matches) => json.aggressiveMinify(Json.obj("tag" -> tag, "matches" -> matches)) })
      Ok(result)
    }
  }

  def getKeepInfoV1(id: ExternalId[Keep], withFullInfo: Boolean, idealImageWidth: Option[Int], idealImageHeight: Option[Int], maxMessagesShown: Int) = UserAction.async { implicit request =>
    getKeepInfo(id, withFullInfo, idealImageWidth, idealImageHeight, maxMessagesShown, true)
  }

  def getKeepInfoV2(id: ExternalId[Keep], withFullInfo: Boolean, idealImageWidth: Option[Int], idealImageHeight: Option[Int], maxMessagesShown: Int) = UserAction.async { implicit request =>
    getKeepInfo(id, withFullInfo, idealImageWidth, idealImageHeight, maxMessagesShown, false)
  }

  private def getKeepInfo(id: ExternalId[Keep], withFullInfo: Boolean, idealImageWidth: Option[Int], idealImageHeight: Option[Int], maxMessagesShown: Int, v1: Boolean)(implicit request: UserRequest[_]) = {
    db.readOnlyMaster { implicit s =>
      keepRepo.getOpt(id).filter(_.isActive)
    } match {
      case None => Future.successful(NotFound(Json.obj("error" -> "not_found")))
      case Some(keep) if withFullInfo =>
        val idealImageSize = {
          for {
            w <- idealImageWidth
            h <- idealImageHeight
          } yield ImageSize(w, h)
        } getOrElse ProcessedImageSize.Large.idealSize
        keepDecorator.decorateKeepsIntoKeepInfos(Some(request.userId), false, Seq(keep), idealImageSize, maxMessagesShown, sanitizeUrls = true).imap {
          case Seq(keepInfo) =>
            Ok(Json.toJson(keepInfo.copy(note = Hashtags.formatMobileNote(keepInfo.note, v1))))
        }
      case Some(keep) =>
        Future.successful(Ok(Json.toJson(PartialKeepInfo.fromKeep(keep.copy(note = Hashtags.formatMobileNote(keep.note, v1))))))
    }
  }

  def editKeepInfoV2(id: ExternalId[Keep]) = UserAction(parse.tolerantJson) { request =>
    val source = KeepEventSource.fromUserAgent(UserAgent(request))
    val resultIfEverythingWentWell = for {
      keepId <- db.readOnlyMaster { implicit s => Try(keepRepo.convertExternalId(id)).toOption }.withLeft(KeepFail.INVALID_KEEP_ID: KeepFail)
      _ <- (request.body \ "note").asOpt[String].fold[RightBias[KeepFail, Unit]](RightBias.unit) { note =>
        keepsCommander.updateKeepNote(keepId, request.userId, note).map(_ => ())
      }
      _ <- (request.body \ "title").asOpt[String].fold[RightBias[KeepFail, Unit]](RightBias.unit) { title =>
        keepsCommander.updateKeepTitle(keepId, request.userId, title, source).map(_ => ())
      }
    } yield ()
    resultIfEverythingWentWell.fold(
      fail => fail.asErrorResponse,
      _ => NoContent
    )
  }

  def getKeepStream(limit: Int, beforeId: Option[String], afterId: Option[String], filterKind: Option[String], filterId: Option[String], maxMessagesShown: Int) = UserAction.async { request =>
    val beforeExtId = beforeId.flatMap(id => ExternalId.asOpt[Keep](id))
    val afterExtId = afterId.flatMap(id => ExternalId.asOpt[Keep](id))
    val filter = filterKind.flatMap(FeedFilter(_, filterId))

    keepsCommander.getKeepStream(request.userId, limit, beforeExtId, afterExtId, maxMessagesShown = maxMessagesShown, sanitizeUrls = true, filterOpt = filter).map { keeps =>
      Ok(Json.obj("keeps" -> keeps))
    }
  }

}
