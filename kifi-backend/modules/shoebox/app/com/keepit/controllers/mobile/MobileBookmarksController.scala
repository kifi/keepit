package com.keepit.controllers.mobile

import com.keepit.commanders._
import com.keepit.heimdal._
import com.keepit.common.controller.{ShoeboxServiceController, MobileController, ActionAuthenticator}
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.model._

import play.api.libs.json.{JsObject, Json}

import com.keepit.common.akka.SafeFuture
import com.google.inject.Inject
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.concurrent.Future
import scala.util.{Success, Failure}

class MobileBookmarksController @Inject() (
  db: Database,
  uriSummaryCommander: URISummaryCommander,
  uriRepo: NormalizedURIRepo,
  pageInfoRepo: PageInfoRepo,
  keepRepo: KeepRepo,
  actionAuthenticator: ActionAuthenticator,
  bookmarksCommander: KeepsCommander,
  collectionCommander: CollectionCommander,
  collectionRepo: CollectionRepo,
  heimdalContextBuilder: HeimdalContextBuilderFactory)
    extends MobileController(actionAuthenticator) with ShoeboxServiceController {

  implicit val writesKeepInfo = new FullKeepInfoWriter(sanitize = true)

  def allKeeps(before: Option[String], after: Option[String], collectionOpt: Option[String], count: Int, withPageInfo: Boolean) = JsonAction.authenticatedAsync { request =>
    bookmarksCommander.allKeeps(before map ExternalId[Keep], after map ExternalId[Keep], collectionOpt map ExternalId[Collection], count, request.userId, withPageInfo) map { res =>
      Ok(Json.obj(
        "collection" -> res._1,
        "before" -> before,
        "after" -> after,
        "keeps" -> res._2
      ))
    }
  }

  def allCollections(sort: String) = JsonAction.authenticatedAsync { request =>
    for {
      numKeeps <- SafeFuture { db.readOnly { implicit s => keepRepo.getCountByUser(request.userId) } }
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
    val fromJson = request.body.as[KeepInfosWithCollection]
    val source = KeepSource.mobile
    implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, source).build
    val (keeps, addedToCollection) = bookmarksCommander.keepMultiple(fromJson, request.userId, source)
    Ok(Json.obj(
      "keeps" -> keeps,
      "addedToCollection" -> addedToCollection
    ))
  }

  def addKeeps() = JsonAction.authenticatedParseJson { request =>
    val fromJson = request.body.as[KeepInfosWithCollection]
    val source = KeepSource.mobile
    implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, source).build
    val (keeps, addedToCollection) = bookmarksCommander.keepMultiple(fromJson, request.userId, source)
    Ok(Json.obj(
      "keepCount" -> keeps.size,
      "addedToCollection" -> addedToCollection
    ))
  }

  def unkeepMultiple() = JsonAction.authenticated { request =>
    implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.mobile).build
    request.body.asJson.flatMap(Json.fromJson[Seq[KeepInfo]](_).asOpt) map { keepInfos =>
      Ok(Json.obj("removedKeeps" -> bookmarksCommander.unkeepMultiple(keepInfos, request.userId)))
    } getOrElse {
      BadRequest(Json.obj("error" -> "parse_error"))
    }
  }

  def unkeep(id: ExternalId[Keep]) = JsonAction.authenticated { request =>
    implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.mobile).build
    bookmarksCommander.unkeep(id, request.userId) map { ki =>
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
      val (deactivatedKeepInfos, errors) = bookmarksCommander.unkeepBatch(ids, request.userId).partition(_._2.isDefined)
      Ok(Json.obj(
        "removedKeeps" -> deactivatedKeepInfos.map(s => s._2.get),
        "errors" -> errors.map(e => Json.obj("id" -> e._1, "error" -> "not_found"))
      ))
    } getOrElse {
      BadRequest(Json.obj("error" -> "parse_error"))
    }
  }

  def saveCollection() = JsonAction.authenticated { request =>
    implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.mobile).build
    collectionCommander.saveCollection("", request.userId, request.body.asJson.flatMap(Json.fromJson[BasicCollection](_).asOpt)) match {
      case Left(newColl) => Ok(Json.toJson(newColl))
      case Right(CollectionSaveFail(message)) => BadRequest(Json.obj("error" -> message))
    }
  }

  def addTag(id: ExternalId[Collection]) = JsonAction.authenticatedParseJson { request =>
    implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.mobile).build
    db.readOnly { implicit s => collectionRepo.getOpt(id) } map { tag =>
      bookmarksCommander.tagUrl(tag, request.body, request.userId, KeepSource.mobile, request.kifiInstallationId)
      Ok(Json.toJson(SendableTag from tag.summary))
    } getOrElse {
      BadRequest(Json.obj("error" -> "noSuchTag"))
    }
  }

  def removeTag(id: ExternalId[Collection]) = JsonAction.authenticatedParseJson { request =>
    val url = (request.body \ "url").as[String]
    implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.mobile).build
    bookmarksCommander.removeTag(id, url, request.userId)
    Ok(Json.obj())
  }

  private def toJsObject(url: String, uri: NormalizedURI, screenshotUrlOpt: Option[String], imageUrlOpt: Option[String]): JsObject = {
    log.info(s"[getImageUrl] returning screenshot $screenshotUrlOpt and image $imageUrlOpt")
    (screenshotUrlOpt, imageUrlOpt) match {
      case (None, None) =>
        Json.obj("url" -> url, "uriId" -> uri.id.get)
      case (None, Some(imgUrl)) =>
        Json.obj("url" -> url, "imgUrl" -> imgUrl)
      case (Some(ssUrl), None) =>
        Json.obj("url" -> url, "screenshotUrl" -> ssUrl)
      case (Some(ssUrl), Some(imgUrl)) =>
        Json.obj("url" -> url, "imgUrl" -> imgUrl, "screenshotUrl" -> ssUrl)
    }
  }

  // todo(ray): consolidate with web endpoint

  def getImageUrl() = JsonAction.authenticatedParseJsonAsync { request => // WIP; test-only
    val urlOpt = (request.body \ "url").asOpt[String]
    log.info(s"[getImageUrl] body=${request.body} url=${urlOpt}")
    urlOpt match {
      case None => Future.successful(BadRequest(Json.obj("code" -> "illegal_argument")))
      case Some(url) => {
        val (uriOpt) = db.readOnly{ implicit ro => uriRepo.getByUri(url) }
        uriOpt match {
          case None => Future.successful(NotFound(Json.obj("code" -> "uri_not_found")))
          case Some(uri) => {
            val screenshotUrlOpt = uriSummaryCommander.getScreenshotURL(uri)
            uriSummaryCommander.getURIImage(uri) map { imageUrlOpt =>
              Ok(toJsObject(url, uri, screenshotUrlOpt, imageUrlOpt))
            }
          }
        }
      }
    }
  }

  def getImageUrls() = JsonAction.authenticatedParseJsonAsync { request => // WIP; test-only
    val urlsOpt = (request.body \ "urls").asOpt[Seq[String]]
    log.info(s"[getImageUrls] body=${request.body} urls=${urlsOpt}")
    urlsOpt match {
      case None => Future.successful(BadRequest(Json.obj("code" -> "illegal_arguments")))
      case Some(urls) => {
        val tuples = db.readOnly { implicit ro =>
          urls.map { s =>
            s -> uriRepo.getByUri(s)
          }
        }
        val tuplesF = tuples map { case (url, uriOpt) =>
          val uriOpt = db.readOnly{ implicit ro => uriRepo.getByUri(url) }
          uriOpt match {
            case None => Future.successful(Json.obj("url" -> url, "code" -> "uri_not_found"))
            case Some(uri) => {
              val screenshotUrlOpt = uriSummaryCommander.getScreenshotURL(uri)
              uriSummaryCommander.getURIImage(uri) map { imageUrlOpt =>
                toJsObject(url, uri, screenshotUrlOpt, imageUrlOpt)
              }
            }
          }
        }
        Future.sequence(tuplesF) map { res =>
          Ok(Json.toJson(res))
        }
      }
    }
  }

}
