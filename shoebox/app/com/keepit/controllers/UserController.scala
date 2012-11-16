package com.keepit.controllers

import play.api.data._
import java.util.concurrent.TimeUnit
import java.sql.Connection
import play.api._
import play.api.Play.current
import play.api.data.Forms._
import play.api.data.validation.Constraints._
import play.api.libs.ws.WS
import play.api.mvc._
import play.api.libs.json.JsArray
import play.api.libs.json.Json
import play.api.libs.json.JsObject
import play.api.libs.json.JsString
import play.api.libs.json.JsValue
import play.api.libs.json.JsNumber
import com.keepit.inject._
import com.keepit.common.time._
import com.keepit.common.net._
import com.keepit.common.db.Id
import com.keepit.common.db.CX
import com.keepit.common.db.ExternalId
import com.keepit.common.logging.Logging
import com.keepit.model._
import com.keepit.serializer.UserWithSocialSerializer._
import com.keepit.serializer.UserWithSocialSerializer
import com.keepit.controllers.CommonActions._
import play.api.http.ContentTypes
import securesocial.core._
import com.keepit.scraper.ScraperPlugin
import com.keepit.common.social._
import com.keepit.common.controller.FortyTwoController
import com.keepit.search.index.ArticleIndexer
import com.keepit.search.graph.URIGraph
import views.html.defaultpages.unauthorized
import org.joda.time.LocalDate
import scala.collection.immutable.Map

case class UserStatistics(user: User, userWithSocial: UserWithSocial, bookmarksByDate: Map[LocalDate, Seq[Bookmark]], socialConnectionCount: Long)

object UserController extends FortyTwoController {

  /**
   * Call me using:
   * curl localhost:9000/users/keepurl?url=http://www.ynet.co.il/;echo
   */
  def usersKeptUrl(url: String, externalId: ExternalId[User]) = Action { request =>
    
    val socialUsers = CX.withConnection { implicit c => 
      NormalizedURI.getByNormalizedUrl(url) match {
        case Some(uri) =>
          val userId = User.getOpt(externalId).getOrElse(
                throw new Exception("externalId %s not found".format(externalId))).id.get
          val friendIds = SocialConnection.getFortyTwoUserConnections(userId)
              
          val articleIndexer = inject[ArticleIndexer]
          val uriGraph = inject[URIGraph]
          
          val uriGraphSearcher = uriGraph.getURIGraphSearcher
          val friendEdgeSet = uriGraphSearcher.getUserToUserEdgeSet(userId, friendIds)
          
          val sharingUserIds = uriGraphSearcher.intersect(friendEdgeSet, uriGraphSearcher.getUriToUserEdgeSet(uri.id.get)).destIdSet - userId
          
          sharingUserIds map (u => UserWithSocial.toUserWithSocial(User.get(u))) toSeq
          
        case None =>
          Seq[UserWithSocial]()
      }
    }

    Ok(userWithSocialSerializer.writes(socialUsers)).as(ContentTypes.JSON)
  }
  
  /**
   * Call me using:
   * $ curl localhost:9000/admin/user/get/all | python -mjson.tool
   */
  def getUsers = Action { request =>
    val users = CX.withConnection { implicit c =>
      User.all map UserWithSocial.toUserWithSocial
    }
    Ok(JsArray(users map { user => 
      JsObject(List(
        "userId" -> JsNumber(user.user.id.get.id),//deprecated, lets stop exposing user id to the outside world. use external id instead.
        "externalId" -> JsString(user.user.externalId.id),
        "userObject" -> userWithSocialSerializer.writes(user)
      ))
    }))
  }

  def getUser(id: Id[User]) = Action { request =>
    val user = CX.withConnection { implicit c =>
      UserWithSocial.toUserWithSocial(User.get(id))
    }
    Ok(userWithSocialSerializer.writes(user))
  }

  def getUserByExternal(id: ExternalId[User]) = Action { request =>
    val user = CX.withConnection { implicit c =>
      UserWithSocial.toUserWithSocial(User.get(id))
    }
    Ok(userWithSocialSerializer.writes(user))
  }
  
  def userStatistics(user: User)(implicit conn: Connection): UserStatistics = {
    val bookmarks = Bookmark.ofUser(user)
    val byDate = bookmarks map {b => (b.createdAt.toLocalDateInZone -> b)} groupBy(_._1) mapValues(_.map(_._2))
    val socialConnectionCount = SocialConnection.getUserConnectionsCount(user.id.get)
    UserStatistics(user, UserWithSocial.toUserWithSocial(user), byDate, socialConnectionCount)
  } 

  def userView(userId: Id[User]) = AdminHtmlAction { implicit request => 
    val (user, bookmarks, socialUserInfos, socialConnections, fortyTwoConnections) = CX.withConnection { implicit c =>
      val userWithSocial = UserWithSocial.toUserWithSocial(User.get(userId)) 
      val bookmarks = Bookmark.ofUser(userWithSocial.user)
      val socialUserInfos = SocialUserInfo.getByUser(userWithSocial.user.id.get)
      val socialConnections = SocialConnection.getUserConnections(userId).sortWith((a,b) => a.fullName < b.fullName)
      val fortyTwoConnections = (SocialConnection.getFortyTwoUserConnections(userId) map (User.get(_)) map UserWithSocial.toUserWithSocial toSeq).sortWith((a,b) => a.socialUserInfo.fullName < b.socialUserInfo.fullName)
      (userWithSocial, bookmarks, socialUserInfos, socialConnections, fortyTwoConnections)
    }
    val rawInfos = socialUserInfos map {info =>
      inject[SocialUserRawInfoStore].get(info.id.get)
    } 
    Ok(views.html.user(user, bookmarks, socialUserInfos, rawInfos.flatten, socialConnections, fortyTwoConnections))
  }

  def usersView = AdminHtmlAction { implicit request => 
    val users = CX.withConnection { implicit c => 
      User.all map userStatistics
    }
    Ok(views.html.users(users))
  }

  def addExperiment(userId: Id[User], experimentType: String) = AdminHtmlAction { request =>
    CX.withConnection { implicit c =>
      val existing = UserExperiment.getByUser(userId)
      val experiment = UserExperiment.ExperimentTypes(experimentType)
      if (existing contains(experimentType)) throw new Exception("user %s already has an experiment %s".format(experimentType))
      UserExperiment(userId = userId, experimentType = experiment).save
    }
    Redirect(request.referer)
  }
  
  def refreshAllSocialInfo(userId: Id[User]) = AdminHtmlAction { implicit request => 
    val socialUserInfos = CX.withConnection { implicit c => 
      val user = User.get(userId)
      SocialUserInfo.getByUser(user.id.get)
    }
    val graph = inject[SocialGraphPlugin]
    socialUserInfos foreach { info =>
      graph.asyncFetch(info)
    }
    Redirect(com.keepit.controllers.routes.UserController.userView(userId))
  }
}
