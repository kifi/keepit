package com.keepit.controllers.mobile

import com.keepit.commanders._
import com.keepit.common.json
import com.keepit.common.json.TupleFormat
import com.keepit.common.net.URISanitizer
import com.keepit.heimdal._
import com.keepit.common.controller.{ ShoeboxServiceController, UserActions, UserActionsHelper }
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.model._
import com.keepit.common.crypto.PublicIdConfiguration

import play.api.libs.json._

import com.keepit.common.akka.SafeFuture
import com.google.inject.Inject
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.concurrent.Future
import com.keepit.common.store.ImageSize
import com.keepit.commanders.CollectionSaveFail
import com.keepit.normalizer.NormalizedURIInterner
import com.keepit.common.core._
import com.keepit.common.json.TupleFormat

class MobileKeepsController @Inject() (
  db: Database,
  keepRepo: KeepRepo,
  val userActionsHelper: UserActionsHelper,
  keepDecorator: KeepDecorator,
  keepsCommander: KeepCommander,
  collectionCommander: CollectionCommander,
  collectionRepo: CollectionRepo,
  normalizedURIInterner: NormalizedURIInterner,
  libraryInfoCommander: LibraryInfoCommander,
  rawBookmarkFactory: RawBookmarkFactory,
  heimdalContextBuilder: HeimdalContextBuilderFactory,
  implicit val publicIdConfig: PublicIdConfiguration)
    extends UserActions with ShoeboxServiceController {

  def allKeepsV1(before: Option[String], after: Option[String], collectionOpt: Option[String], helprankOpt: Option[String], count: Int, withPageInfo: Boolean) = UserAction.async { request =>
    getAllKeeps(request.userId, before, after, collectionOpt, helprankOpt, count, withPageInfo, true)
  }

  def allKeepsV2(before: Option[String], after: Option[String], collectionOpt: Option[String], helprankOpt: Option[String], count: Int, withPageInfo: Boolean) = UserAction.async { request =>
    getAllKeeps(request.userId, before, after, collectionOpt, helprankOpt, count, withPageInfo, false)
  }

  private def getAllKeeps(userId: Id[User], before: Option[String], after: Option[String], collectionOpt: Option[String], helprankOpt: Option[String], count: Int, withPageInfo: Boolean, v1: Boolean) = {
    keepsCommander.allKeeps(before map ExternalId[Keep], after map ExternalId[Keep], collectionOpt map ExternalId[Collection], helprankOpt, count, userId) map { res =>
      val basicCollection = collectionOpt.flatMap { collStrExtId =>
        ExternalId.asOpt[Collection](collStrExtId).flatMap { collExtId =>
          db.readOnlyMaster(collectionRepo.getByUserAndExternalId(userId, collExtId)(_)).map { c =>
            BasicCollection.fromCollection(c.summary)
          }
        }
      }
      val helprank = helprankOpt map (selector => Json.obj("helprank" -> selector)) getOrElse Json.obj()
      val sanitizedKeeps = res.map(k => k.copy(url = URISanitizer.sanitize(k.url), note = Hashtags.formatMobileNote(k.note, v1)))
      Ok(Json.obj(
        "collection" -> basicCollection,
        "before" -> before,
        "after" -> after,
        "keeps" -> Json.toJson(sanitizedKeeps)
      ) ++ helprank)
    }
  }

  def allCollections(sort: String) = UserAction.async { request =>
    for {
      numKeeps <- SafeFuture { db.readOnlyMaster { implicit s => keepRepo.getCountByUser(request.userId) } }
      collections <- SafeFuture { collectionCommander.allCollections(sort, request.userId) }
    } yield {
      Ok(Json.obj(
        "keeps" -> numKeeps,
        "collections" -> collections
      ))
    }
  }

  def suggestTags(keepIdOptStringHack: Option[String], query: Option[String], limit: Int) = UserAction.async { request =>
    val keepIdOpt = keepIdOptStringHack.map(ExternalId.apply[Keep])
    keepsCommander.suggestTags(request.userId, keepIdOpt, query, limit).imap { tagsAndMatches =>
      implicit val matchesWrites = TupleFormat.tuple2Writes[Int, Int]
      val result = JsArray(tagsAndMatches.map { case (tag, matches) => json.aggressiveMinify(Json.obj("tag" -> tag, "matches" -> matches)) })
      Ok(result)
    }
  }

  def getKeepInfoV1(id: ExternalId[Keep], withFullInfo: Boolean, idealImageWidth: Option[Int], idealImageHeight: Option[Int]) = UserAction.async { request =>
    getKeepInfo(request.userIdOpt, id, withFullInfo, idealImageWidth, idealImageHeight, true)
  }

  def getKeepInfoV2(id: ExternalId[Keep], withFullInfo: Boolean, idealImageWidth: Option[Int], idealImageHeight: Option[Int]) = UserAction.async { request =>
    getKeepInfo(request.userIdOpt, id, withFullInfo, idealImageWidth, idealImageHeight, false)
  }

  private def getKeepInfo(userIdOpt: Option[Id[User]], id: ExternalId[Keep], withFullInfo: Boolean, idealImageWidth: Option[Int], idealImageHeight: Option[Int], v1: Boolean) = {
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
        keepDecorator.decorateKeepsIntoKeepInfos(userIdOpt, false, Seq(keep), idealImageSize, sanitizeUrls = true).imap {
          case Seq(keepInfo) =>
            Ok(Json.toJson(keepInfo.copy(note = Hashtags.formatMobileNote(keepInfo.note, v1))))
        }
      case Some(keep) =>
        Future.successful(Ok(Json.toJson(KeepInfo.fromKeep(keep.copy(note = Hashtags.formatMobileNote(keep.note, v1))))))
    }
  }

  def editKeepInfoV2(id: ExternalId[Keep]) = UserAction(parse.tolerantJson) { request =>
    db.readOnlyMaster { implicit s =>
      keepRepo.getOpt(id).filter(_.isActive)
    } match {
      case None =>
        NotFound(Json.obj("error" -> "not_found"))
      case Some(keep) =>
        val json = request.body
        val titleOpt = (json \ "title").asOpt[String]
        val noteOpt = (json \ "note").asOpt[String]

        val titleToPersist = (titleOpt orElse keep.title) map (_.trim) filterNot (_.isEmpty)
        val noteToPersist = (noteOpt orElse keep.note) map (_.trim) filterNot (_.isEmpty)

        if (titleToPersist != keep.title || noteToPersist != keep.note) {
          db.readWrite { implicit s =>
            val updatedKeep = keepRepo.save(keep.withTitle(titleToPersist))
            keepsCommander.updateKeepNote(request.userId, updatedKeep, noteToPersist.getOrElse(""))
          }
        }

        NoContent
    }
  }

  def getKeepStream(limit: Int, beforeId: Option[String], afterId: Option[String]) = UserAction.async { request =>
    val beforeExtId = beforeId.flatMap(id => ExternalId.asOpt[Keep](id))
    val afterExtId = afterId.flatMap(id => ExternalId.asOpt[Keep](id))

    keepsCommander.getKeepStream(request.userId, limit, beforeExtId, afterExtId, sanitizeUrls = true).map { keeps =>
      Ok(Json.obj("keeps" -> keeps))
    }
  }

}
