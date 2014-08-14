package com.keepit.controllers.mobile

import com.google.inject.Inject

import com.keepit.commanders._
import com.keepit.common.akka.SafeFuture
import com.keepit.common.controller._
import com.keepit.common.db.slick._
import com.keepit.model._
import play.api.libs.json.Json._
import play.api.libs.json._
import play.api.libs.concurrent.Execution.Implicits.defaultContext

class MobilePageController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  db: Database,
  keepRepo: KeepRepo,
  userCommander: UserCommander,
  collectionCommander: CollectionCommander,
  pageCommander: PageCommander)
    extends BrowserExtensionController(actionAuthenticator) with ShoeboxServiceController {

  def getPageDetails() = JsonAction.authenticatedParseJson { request =>
    val url = (request.body \ "url").as[String]
    val info = pageCommander.getPageDetails(url, request.userId, request.experiments)
    Ok(Json.toJson(info))
  }

  def queryExtension(page: Int, pageSize: Int) = JsonAction.authenticatedParseJsonAsync { request =>

    val url = (request.body \ "url").as[String]
    val sortOrder = "user"

    // page details
    val pageFutures = SafeFuture { pageCommander.getPageDetails(url, request.userId, request.experiments) }
    // user infos
    val basicUserFutures = SafeFuture { userCommander.getUserInfo(request.user) }
    val userAttributeFutures = userCommander.getKeepAttributionInfo(request.userId)
    // keeps & collections
    val numKeepsFuture = SafeFuture { db.readOnlyMaster { implicit s => keepRepo.getCountByUser(request.userId) } }
    val collectionsFuture = SafeFuture { collectionCommander.allCollections(sortOrder, request.userId) }
    // friend connections
    val friendsFuture = SafeFuture { userCommander.getConnectionsPage(request.userId, page, pageSize) }

    val tupleFuture = for {
      pageInfo <- pageFutures
      basicUserInfo <- basicUserFutures
      userAttributeInfo <- userAttributeFutures
      numKeeps <- numKeepsFuture
      collections <- collectionsFuture
      friendConnections <- friendsFuture
    } yield {
      (pageInfo, basicUserInfo, userAttributeInfo, numKeeps, collections, friendConnections)
    }

    tupleFuture.map { tuple =>
      val pageInfo = tuple._1
      val basicUserInfo = tuple._2
      val attributionInfo = tuple._3
      val numKeeps = tuple._4
      val collections = tuple._5
      val (connectionsPage, total) = tuple._6
      val friendsJsons = connectionsPage.map {
        case ConnectionInfo(friend, _, unfriended, unsearched) =>
          Json.toJson(friend).asInstanceOf[JsObject] ++ Json.obj(
            "searchFriend" -> unsearched,
            "unfriended" -> unfriended
          )
      }
      Ok(
        Json.obj(
          "pageInfo" -> pageInfo,
          "userInfo" -> (toJson(basicUserInfo.basicUser).as[JsObject] ++
            toJson(basicUserInfo.info).as[JsObject] ++
            Json.obj(
              "notAuthed" -> basicUserInfo.notAuthed,
              "experiments" -> request.experiments.map(_.value),
              "clickCount" -> attributionInfo.clickCount,
              "rekeepCount" -> attributionInfo.rekeepCount,
              "rekeepTotalCount" -> attributionInfo.rekeepTotalCount
            )),
          "numKeeps" -> numKeeps,
          "collections" -> collections,
          "friends" -> friendsJsons
        )
      )
    }
  }

}
