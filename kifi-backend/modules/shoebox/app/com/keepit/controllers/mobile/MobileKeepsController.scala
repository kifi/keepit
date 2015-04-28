package com.keepit.controllers.mobile

import com.keepit.commanders._
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
import scala.Some
import com.keepit.normalizer.NormalizedURIInterner
import com.keepit.common.core._

class MobileKeepsController @Inject() (
  db: Database,
  uriSummaryCommander: URISummaryCommander,
  uriRepo: NormalizedURIRepo,
  pageInfoRepo: PageInfoRepo,
  keepRepo: KeepRepo,
  val userActionsHelper: UserActionsHelper,
  keepDecorator: KeepDecorator,
  keepsCommander: KeepsCommander,
  collectionCommander: CollectionCommander,
  collectionRepo: CollectionRepo,
  normalizedURIInterner: NormalizedURIInterner,
  libraryCommander: LibraryCommander,
  rawBookmarkFactory: RawBookmarkFactory,
  hashtagCommander: HashtagCommander,
  heimdalContextBuilder: HeimdalContextBuilderFactory,
  implicit val publicIdConfig: PublicIdConfiguration)
    extends UserActions with ShoeboxServiceController {

  def allKeeps(before: Option[String], after: Option[String], collectionOpt: Option[String], helprankOpt: Option[String], count: Int, withPageInfo: Boolean) = UserAction.async { request =>
    keepsCommander.allKeeps(before map ExternalId[Keep], after map ExternalId[Keep], collectionOpt map ExternalId[Collection], helprankOpt, count, request.userId) map { res =>
      val basicCollection = collectionOpt.flatMap { collStrExtId =>
        ExternalId.asOpt[Collection](collStrExtId).flatMap { collExtId =>
          db.readOnlyMaster(collectionRepo.getByUserAndExternalId(request.userId, collExtId)(_)).map { c =>
            BasicCollection.fromCollection(c.summary)
          }
        }
      }
      val helprank = helprankOpt map (selector => Json.obj("helprank" -> selector)) getOrElse Json.obj()
      val sanitizedKeeps = res.map(k => k.copy(url = URISanitizer.sanitize(k.url)))
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

  def keepMultiple() = UserAction(parse.tolerantJson) { request =>
    val fromJson = request.body.as[RawBookmarksWithCollection]
    val source = KeepSource.mobile
    implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, source).build
    val libraryId = {
      db.readWrite { implicit session =>
        val (main, secret) = libraryCommander.getMainAndSecretLibrariesForUser(request.userId)
        fromJson.keeps.headOption.flatMap(_.isPrivate).map { priv =>
          if (priv) secret.id.get else main.id.get
        }.getOrElse(main.id.get)
      }
    }
    fromJson.keeps.headOption
    val (keeps, addedToCollection, _, _) = keepsCommander.keepMultiple(fromJson.keeps, libraryId, request.userId, source, fromJson.collection)
    Ok(Json.obj(
      "keeps" -> keeps,
      "addedToCollection" -> addedToCollection
    ))
  }

  def addKeepWithTags() = UserAction(parse.tolerantJson) { request =>
    val json = request.body
    val rawBookmark = (json \ "keep").as[RawBookmarkRepresentation]
    val collectionNames = (json \ "tagNames").as[Seq[String]]
    val source = KeepSource.mobile
    implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, source).build
    val libraryId = {
      db.readWrite { implicit session =>
        val (main, secret) = libraryCommander.getMainAndSecretLibrariesForUser(request.userId)
        rawBookmark.isPrivate.map { priv =>
          if (priv) secret.id.get else main.id.get
        }.getOrElse(main.id.get)
      }
    }
    keepsCommander.keepWithSelectedTags(request.userId, rawBookmark, libraryId, source, collectionNames, SocialShare(json)) match {
      case Left(msg) => BadRequest(msg)
      case Right((keep, tags)) =>
        Ok(Json.obj(
          "keep" -> Json.toJson(KeepInfo.fromKeep(keep)),
          "tags" -> tags.map(tag => Json.obj("name" -> tag.name, "id" -> tag.externalId))
        ))
    }
  }

  def unkeepMultiple() = UserAction { request =>
    implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.mobile).build
    request.body.asJson.flatMap(Json.fromJson[Seq[RawBookmarkRepresentation]](_).asOpt) map { rawBookmarks =>
      Ok(Json.obj("removedKeeps" -> keepsCommander.unkeepMultiple(rawBookmarks, request.userId)))
    } getOrElse {
      BadRequest(Json.obj("error" -> "parse_error"))
    }
  }

  def saveCollection() = UserAction { request =>
    implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.mobile).build
    collectionCommander.saveCollection(request.userId, request.body.asJson.flatMap(Json.fromJson[BasicCollection](_).asOpt)) match {
      case Left(newColl) => Ok(Json.toJson(newColl))
      case Right(CollectionSaveFail(message)) => BadRequest(Json.obj("error" -> message))
    }
  }

  def addTag(id: ExternalId[Collection]) = UserAction(parse.tolerantJson) { request =>
    implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.mobile).build
    db.readOnlyMaster { implicit s => collectionRepo.getOpt(id) } map { tag =>
      val rawBookmarks = rawBookmarkFactory.toRawBookmarks(request.body)
      val libraryId = {
        db.readWrite { implicit session =>
          val libIdOpt = for {
            url <- rawBookmarks.headOption.map(_.url)
            uri <- normalizedURIInterner.getByUri(url)
            keep <- keepRepo.getByUriAndUser(uri.id.get, request.userId)
            libraryId <- keep.libraryId
          } yield libraryId
          libIdOpt.getOrElse {
            libraryCommander.getMainAndSecretLibrariesForUser(request.userId)._2.id.get // default to secret library if we can't find the keep
          }
        }
      }
      keepsCommander.tagUrl(tag, rawBookmarks, request.userId, libraryId, KeepSource.mobile, request.kifiInstallationId)
      Ok(Json.toJson(SendableTag from tag.summary))
    } getOrElse {
      BadRequest(Json.obj("error" -> "noSuchTag"))
    }
  }

  def removeTag(id: ExternalId[Collection]) = UserAction(parse.tolerantJson) { request =>
    val url = (request.body \ "url").as[String]
    implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.mobile).build
    keepsCommander.removeTag(id, url, request.userId)
    Ok(Json.obj())
  }

  def getKeepInfo(id: ExternalId[Keep], withFullInfo: Boolean, idealImageWidth: Option[Int], idealImageHeight: Option[Int]) = UserAction.async { request =>
    val keepOpt = db.readOnlyMaster { implicit s => keepRepo.getOpt(id).filter(_.isActive) }

    keepOpt match {
      case None => Future.successful(NotFound(Json.obj("error" -> "not_found")))
      case Some(keep) if withFullInfo =>
        val idealImageSize = {
          for {
            w <- idealImageWidth
            h <- idealImageHeight
          } yield ImageSize(w, h)
        } getOrElse ProcessedImageSize.Large.idealSize
        keepDecorator.decorateKeepsIntoKeepInfos(request.userIdOpt, false, Seq(keep), idealImageSize, withKeepTime = true).imap {
          case Seq(keepInfo) =>
            val editedNote = keepInfo.note.map { note =>
              // remove hashtags, then turn all '[\#' -> '[#'
              val noteWithoutHashtags = hashtagCommander.removeAllHashtagsFromString(note)
              keepDecorator.unescapeMarkupNotes(noteWithoutHashtags).trim
            } filterNot (_.isEmpty)
            Ok(Json.toJson(keepInfo.copy(note = editedNote)))
        }
      case Some(keep) => Future.successful(Ok(Json.toJson(KeepInfo.fromKeep(keep))))
    }
  }

  def editKeepInfo(id: ExternalId[Keep]) = UserAction(parse.tolerantJson) { request =>
    val keepOpt = db.readOnlyMaster { implicit s => keepRepo.getOpt(id).filter(_.isActive) }
    keepOpt match {
      case None =>
        NotFound(Json.obj("error" -> "not_found"))
      case Some(keep) =>
        val json = request.body
        val titleOpt = (json \ "title").asOpt[String]
        val noteOpt = (json \ "note").asOpt[String]
        val tagsOpt = (json \ "tags").asOpt[Seq[String]]

        val titleToPersist = (titleOpt orElse keep.title) map (_.trim) filterNot (_.isEmpty)
        val noteToPersist = (noteOpt orElse keep.note).map { note =>
          keepDecorator.escapeMarkupNotes(note).trim
        }.filter(_.nonEmpty)

        val tagsToPersist = tagsOpt.getOrElse(
          db.readOnlyMaster { implicit s =>
            collectionRepo.getHashtagsByKeepId(keep.id.get).map(_.tag).toSeq
          }
        )
        val updatedKeep = keep.copy(title = titleToPersist, note = noteToPersist)

        implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.mobile).build
        db.readWrite { implicit s =>
          keepsCommander.persistHashtagsForKeep(request.userId, updatedKeep, tagsToPersist)(s, context)
        }
        NoContent
    }
  }

  def numKeeps() = UserAction { request =>
    Ok(Json.obj(
      "numKeeps" -> keepsCommander.numKeeps(request.userId)
    ))
  }

}
