package com.keepit.controllers.mobile

import com.keepit.commanders._
import com.keepit.common.net.URISanitizer
import com.keepit.heimdal._
import com.keepit.common.controller.{ ShoeboxServiceController, MobileController, ActionAuthenticator }
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.model._

import play.api.libs.json._

import com.keepit.common.akka.SafeFuture
import com.google.inject.Inject
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.concurrent.Future
import com.keepit.common.store.ImageSize
import com.keepit.commanders.CollectionSaveFail
import scala.Some
import com.keepit.normalizer.NormalizedURIInterner

class MobileBookmarksController @Inject() (
  db: Database,
  uriSummaryCommander: URISummaryCommander,
  uriRepo: NormalizedURIRepo,
  pageInfoRepo: PageInfoRepo,
  keepRepo: KeepRepo,
  actionAuthenticator: ActionAuthenticator,
  keepsCommander: KeepsCommander,
  collectionCommander: CollectionCommander,
  collectionRepo: CollectionRepo,
  normalizedURIInterner: NormalizedURIInterner,
  libraryCommander: LibraryCommander,
  rawBookmarkFactory: RawBookmarkFactory,
  heimdalContextBuilder: HeimdalContextBuilderFactory)
    extends MobileController(actionAuthenticator) with ShoeboxServiceController {

  def allKeeps(before: Option[String], after: Option[String], collectionOpt: Option[String], helprankOpt: Option[String], count: Int, withPageInfo: Boolean) = JsonAction.authenticatedAsync { request =>
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

  def allCollections(sort: String) = JsonAction.authenticatedAsync { request =>
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

  @deprecated(message = "use addKeeps instead", since = "2014-03-28")
  def keepMultiple() = JsonAction.authenticatedParseJson { request =>
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

  def addKeeps() = JsonAction.authenticatedParseJson { request =>
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
    val (keeps, addedToCollection, _, _) = keepsCommander.keepMultiple(fromJson.keeps, libraryId, request.userId, source, fromJson.collection)
    Ok(Json.obj(
      "keepCount" -> keeps.size,
      "addedToCollection" -> addedToCollection
    ))
  }

  def addKeepWithTags() = JsonAction.authenticatedParseJson { request =>
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
    keepsCommander.keepWithSelectedTags(request.userId, rawBookmark, libraryId, source, collectionNames) match {
      case Left(msg) => BadRequest(msg)
      case Right((keepInfo, tags)) =>
        Ok(Json.obj(
          "keep" -> Json.toJson(keepInfo),
          "tags" -> tags.map(tag => Json.obj("name" -> tag.name, "id" -> tag.externalId))
        ))
    }
  }

  def unkeepMultiple() = JsonAction.authenticated { request =>
    implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.mobile).build
    request.body.asJson.flatMap(Json.fromJson[Seq[RawBookmarkRepresentation]](_).asOpt) map { rawBookmarks =>
      Ok(Json.obj("removedKeeps" -> keepsCommander.unkeepMultiple(rawBookmarks, request.userId)))
    } getOrElse {
      BadRequest(Json.obj("error" -> "parse_error"))
    }
  }

  def unkeep(id: ExternalId[Keep]) = JsonAction.authenticated { request =>
    implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.mobile).build
    keepsCommander.unkeep(id, request.userId) map { ki =>
      Ok(Json.toJson(ki))
    } getOrElse {
      NotFound(Json.obj("error" -> "not_found"))
    }
  }

  def unkeepBatch() = JsonAction.authenticatedParseJson { request =>
    implicit val keepFormat = ExternalId.format[Keep]
    val idsOpt = (request.body \ "ids").asOpt[Seq[ExternalId[Keep]]]
    idsOpt map { ids =>
      implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.mobile).build
      val (successes, failures) = keepsCommander.unkeepBatch(ids, request.userId)
      Ok(Json.obj(
        "removedKeeps" -> successes,
        "errors" -> failures.map(id => Json.obj("id" -> id, "error" -> "not_found"))
      ))
    } getOrElse {
      BadRequest(Json.obj("error" -> "parse_error"))
    }
  }

  def saveCollection() = JsonAction.authenticated { request =>
    implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.mobile).build
    collectionCommander.saveCollection(request.userId, request.body.asJson.flatMap(Json.fromJson[BasicCollection](_).asOpt)) match {
      case Left(newColl) => Ok(Json.toJson(newColl))
      case Right(CollectionSaveFail(message)) => BadRequest(Json.obj("error" -> message))
    }
  }

  def addTag(id: ExternalId[Collection]) = JsonAction.authenticatedParseJson { request =>
    implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.mobile).build
    db.readOnlyMaster { implicit s => collectionRepo.getOpt(id) } map { tag =>
      val rawBookmarks = rawBookmarkFactory.toRawBookmarks(request.body)
      val libraryId = {
        db.readWrite { implicit session =>
          val libIdOpt = for {
            url <- rawBookmarks.headOption.map(_.url)
            uri <- normalizedURIInterner.getByUri(url)
            keep <- keepRepo.getInDisjointByUriAndUser(uri.id.get, request.userId)
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

  def removeTag(id: ExternalId[Collection]) = JsonAction.authenticatedParseJson { request =>
    val url = (request.body \ "url").as[String]
    implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.mobile).build
    keepsCommander.removeTag(id, url, request.userId)
    Ok(Json.obj())
  }

  private def toJsObject(
    url: String,
    uri: NormalizedURI,
    screenshotUrlOpt: Option[String],
    imageUrlOpt: Option[String],
    imageWidthOpt: Option[Int],
    imageHeightOpt: Option[Int]): JsObject = {
    log.info(s"[getImageUrl] returning screenshot $screenshotUrlOpt and image $imageUrlOpt")
    val main = (screenshotUrlOpt, imageUrlOpt) match {
      case (None, None) =>
        Json.obj("url" -> url, "uriId" -> uri.id.get)
      case (None, Some(imgUrl)) =>
        Json.obj("url" -> url, "imgUrl" -> imgUrl)
      case (Some(ssUrl), None) =>
        Json.obj("url" -> url, "screenshotUrl" -> ssUrl)
      case (Some(ssUrl), Some(imgUrl)) =>
        Json.obj("url" -> url, "imgUrl" -> imgUrl, "screenshotUrl" -> ssUrl)
    }
    val width = (imageWidthOpt map { width => Json.obj("imgWidth" -> width) } getOrElse Json.obj())
    val height = (imageHeightOpt map { height => Json.obj("imgHeight" -> height) } getOrElse Json.obj())
    main ++ width ++ height
  }

  // todo(ray): consolidate with web endpoint

  def getImageUrl() = JsonAction.authenticatedParseJsonAsync { request => // WIP; test-only
    val urlOpt = (request.body \ "url").asOpt[String]
    log.info(s"[getImageUrl] body=${request.body} url=${urlOpt}")
    urlOpt match {
      case Some(url) => {
        var minSizeOpt = (for {
          minWidth <- (request.body \ "minWidth").asOpt[Int]
          minHeight <- (request.body \ "minHeight").asOpt[Int]
        } yield ImageSize(minWidth, minHeight))
        val uriOpt = db.readOnlyMaster { implicit ro => normalizedURIInterner.getByUri(url) }
        uriOpt match {
          case None => Future.successful(NotFound(Json.obj("code" -> "uri_not_found")))
          case Some(uri) => {
            val screenshotUrlOpt = uriSummaryCommander.getScreenshotURL(uri)
            uriSummaryCommander.getImageURISummary(uri, minSizeOpt) map { uriSummary =>
              Ok(toJsObject(url, uri, screenshotUrlOpt, uriSummary.imageUrl, uriSummary.imageWidth, uriSummary.imageHeight))
            }
          }
        }
      }
      case None => Future.successful(BadRequest(Json.obj("code" -> "illegal_argument")))
    }
  }

  def getImageUrls() = JsonAction.authenticatedParseJsonAsync { request => // WIP; test-only
    val urlsOpt = (request.body \ "urls").asOpt[Seq[JsValue]]
    log.info(s"[getImageUrls] body=${request.body} urls=${urlsOpt}")
    urlsOpt match {
      case None => Future.successful(BadRequest(Json.obj("code" -> "illegal_arguments")))
      case Some(urls) => {
        val uriOpts = db.readOnlyMaster { implicit ro =>
          urls flatMap { urlReq =>
            urlReq match {
              case JsString(url) => Some((url, normalizedURIInterner.getByUri(url), None))
              case _ => {
                val urlOpt = (urlReq \ "url").asOpt[String]
                urlOpt map { url =>
                  val minSizeOpt = for {
                    minWidth <- (urlReq \ "minWidth").asOpt[Int]
                    minHeight <- (urlReq \ "minHeight").asOpt[Int]
                  } yield ImageSize(minWidth, minHeight)
                  (url, normalizedURIInterner.getByUri(url), minSizeOpt)
                }
              }
            }
          }
        }
        val resFutSeq = uriOpts map {
          case (url, uriOpt, minSizeOpt) =>
            uriOpt match {
              case None => Future.successful(Json.obj("url" -> url, "code" -> "uri_not_found"))
              case Some(uri) => {
                val screenshotUrlOpt = uriSummaryCommander.getScreenshotURL(uri)
                uriSummaryCommander.getImageURISummary(uri, minSizeOpt) map { uriSummary =>
                  toJsObject(url, uri, screenshotUrlOpt, uriSummary.imageUrl, uriSummary.imageWidth, uriSummary.imageHeight)
                }
              }
            }
        }
        Future.sequence(resFutSeq) map { res =>
          Ok(Json.toJson(res))
        }
      }
    }
  }

}
