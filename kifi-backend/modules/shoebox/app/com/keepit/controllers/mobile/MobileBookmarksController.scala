package com.keepit.controllers.mobile

import com.keepit.commanders._
import com.keepit.commanders.KeepInfosWithCollection._

import com.keepit.commanders._
import com.keepit.heimdal._
import com.keepit.common.controller.{ShoeboxServiceController, MobileController, ActionAuthenticator}
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import com.keepit.model._
import com.keepit.common.time._
import com.keepit.commanders.{UserCommander, BasicSocialUser}

import play.api.Play.current
import play.api.libs.json.{JsObject, Json, JsValue}

import com.keepit.common.akka.SafeFuture
import com.google.inject.Inject
import com.keepit.common.net.URI
import com.keepit.social.BasicUser
import com.keepit.common.analytics.{Event, EventFamilies, Events}
import play.api.libs.concurrent.Akka
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.common.store.S3ScreenshotStore
import scala.concurrent.Future

class MobileBookmarksController @Inject() (
  db: Database,
  s3ScreenshotStore: S3ScreenshotStore,
  uriRepo: NormalizedURIRepo,
  pageInfoRepo: PageInfoRepo,
  keepRepo: KeepRepo,
  actionAuthenticator: ActionAuthenticator,
  bookmarksCommander: BookmarksCommander,
  collectionCommander: CollectionCommander,
  collectionRepo: CollectionRepo,
  heimdalContextBuilder: HeimdalContextBuilderFactory)
    extends MobileController(actionAuthenticator) with ShoeboxServiceController {

  implicit val writesKeepInfo = new FullKeepInfoWriter()

  def allKeeps(before: Option[String], after: Option[String], collectionOpt: Option[String], count: Int) = JsonAction.authenticatedAsync { request =>
    bookmarksCommander.allKeeps(before map ExternalId[Keep], after map ExternalId[Keep], collectionOpt map ExternalId[Collection], count, request.userId) map { res =>
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

  def keepMultiple() = JsonAction.authenticated { request =>
    request.body.asJson.flatMap(Json.fromJson[KeepInfosWithCollection](_).asOpt) map { fromJson =>
      val source = KeepSource.mobile
      implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, source).build
      val (keeps, addedToCollection) = bookmarksCommander.keepMultiple(fromJson, request.userId, source)
      Ok(Json.obj(
        "keeps" -> keeps,
        "addedToCollection" -> addedToCollection
      ))
    } getOrElse {
      log.error(s"can't parse object from request ${request.body} for user ${request.user}")
      BadRequest(Json.obj("error" -> "Could not parse object from request body"))
    }
  }

  def unkeepMultiple() = JsonAction.authenticated { request =>
    implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.mobile).build
    request.body.asJson.flatMap(Json.fromJson[Seq[KeepInfo]](_).asOpt) map { keepInfos =>
      val deactivatedKeepInfos = bookmarksCommander.unkeepMultiple(keepInfos, request.userId)
      Ok(Json.obj(
        "removedKeeps" -> deactivatedKeepInfos
      ))
    } getOrElse {
      BadRequest(Json.obj("error" -> "Could not parse JSON array of keep with url from request body"))
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
      Ok(Json.toJson(SendableTag from tag))
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

  private def toJsObject(url: String, uri: NormalizedURI, pageInfoOpt: Option[PageInfo]): Future[JsObject] = {
    val screenshotUrlOpt = s3ScreenshotStore.getScreenshotUrl(uri)
    s3ScreenshotStore.asyncGetImageUrl(uri, pageInfoOpt, false) map { imgUrlOpt =>
      (screenshotUrlOpt, imgUrlOpt) match {
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
  }

  // todo(ray): consolidate with web endpoint

  def getImageUrl() = JsonAction.authenticatedParseJsonAsync { request => // WIP; test-only
    val urlOpt = (request.body \ "url").asOpt[String]
    log.info(s"[getImageUrl] body=${request.body} url=${urlOpt}")
    urlOpt match {
      case None => Future.successful(BadRequest(Json.obj("code" -> "illegal_argument")))
      case Some(url) => {
        val (uriOpt, pageInfoOpt) = db.readOnly{ implicit ro =>
          val uriOpt = uriRepo.getByUri(url)
          val pageInfoOpt = uriOpt flatMap { uri => pageInfoRepo.getByUri(uri.id.get) }
          (uriOpt, pageInfoOpt)
        }
        uriOpt match {
          case None => Future.successful(NotFound(Json.obj("code" -> "uri_not_found")))
          case Some(uri) => {
            toJsObject(url, uri, pageInfoOpt) map { js => Ok(js) }
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
          val (uriOpt, pageInfoOpt) = db.readOnly{ implicit ro =>
            val uriOpt = uriRepo.getByUri(url)
            val pageInfoOpt = uriOpt flatMap { uri => pageInfoRepo.getByUri(uri.id.get) }
            (uriOpt, pageInfoOpt)
          }
          uriOpt match {
            case None => Future.successful(Json.obj("url" -> url, "code" -> "uri_not_found"))
            case Some(uri) => {
              toJsObject(url, uri, pageInfoOpt) // todo: batch
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
