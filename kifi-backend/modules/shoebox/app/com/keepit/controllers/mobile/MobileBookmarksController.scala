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
import com.keepit.controllers.core.NetworkInfoLoader
import com.keepit.social.BasicUser
import com.keepit.common.analytics.{Event, EventFamilies, Events}
import play.api.libs.concurrent.Akka
import play.api.libs.concurrent.Execution.Implicits.defaultContext

class MobileBookmarksController @Inject() (
  db: Database,
  bookmarkRepo: BookmarkRepo,
  actionAuthenticator: ActionAuthenticator,
  bookmarksCommander: BookmarksCommander,
  collectionCommander: CollectionCommander,
  heimdalContextBuilder: HeimdalContextBuilderFactory)
    extends MobileController(actionAuthenticator) with ShoeboxServiceController {

  implicit val writesKeepInfo = new FullKeepInfoWriter()

  def allKeeps(before: Option[String], after: Option[String], collectionOpt: Option[String], count: Int) = AuthenticatedJsonAction { request =>
    Async {
      bookmarksCommander.allKeeps(before map ExternalId[Bookmark], after map ExternalId[Bookmark], collectionOpt map ExternalId[Collection], count, request.userId) map { res =>
        Ok(Json.obj(
          "collection" -> res._1,
          "before" -> before,
          "after" -> after,
          "keeps" -> res._2
        ))
      }
    }
  }

  def allCollections(sort: String) = AuthenticatedJsonAction { request =>
    Async {
      for {
        numKeeps <- SafeFuture { db.readOnly { implicit s => bookmarkRepo.getCountByUser(request.userId) } }
        collections <- SafeFuture { collectionCommander.allCollections(sort, request.userId) }
      } yield {
        Ok(Json.obj(
          "keeps" -> numKeeps,
          "collections" -> collections
        ))
      }
    }
  }

  def keepMultiple() = AuthenticatedJsonAction { request =>
    request.body.asJson.flatMap(Json.fromJson[KeepInfosWithCollection](_).asOpt) map { fromJson =>
      val contextBuilder = heimdalContextBuilder()
      contextBuilder.addRequestInfo(request)
      val source = BookmarkSource.mobile
      val (keeps, addedToCollection) = bookmarksCommander.keepMultiple(fromJson, request.user, request.experiments, contextBuilder, source)
      Ok(Json.obj(
        "keeps" -> keeps,
        "addedToCollection" -> addedToCollection
      ))
    } getOrElse {
      log.error(s"can't parse object from request ${request.body} for user ${request.user}")
      BadRequest(Json.obj("error" -> "Could not parse object from request body"))
    }
  }

  def unkeepMultiple() = AuthenticatedJsonAction { request =>
    request.body.asJson.flatMap(Json.fromJson[Seq[KeepInfo]](_).asOpt) map { keepInfos =>
      val deactivatedKeepInfos = bookmarksCommander.unkeepMultiple(keepInfos, request.userId)
      Ok(Json.obj(
        "removedKeeps" -> deactivatedKeepInfos
      ))
    } getOrElse {
      BadRequest(Json.obj("error" -> "Could not parse JSON array of keep with url from request body"))
    }
  }

  def saveCollection() = AuthenticatedJsonAction { request =>
    collectionCommander.saveCollection("", request.userId, request.body.asJson.flatMap(Json.fromJson[BasicCollection](_).asOpt)) match {
      case Left(newColl) => Ok(Json.toJson(newColl))
      case Right(CollectionSaveFail(message)) => BadRequest(Json.obj("error" -> message))
    }
  }

}
