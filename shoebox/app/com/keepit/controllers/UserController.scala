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
import play.api.libs.json.{Json, JsArray, JsBoolean, JsNumber, JsObject, JsString, JsValue}
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
import com.keepit.serializer.BasicUserSerializer
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
import play.api.libs.json.JsArray

case class UserStatistics(user: User, userWithSocial: UserWithSocial, socialConnectionCount: Long)

object UserController extends FortyTwoController {

  def getSliderInfo(url: String) = AuthenticatedJsonAction { request =>
    val (kept, following, socialUsers, numComments, numMessages) = CX.withConnection { implicit c =>
      NormalizedURI.getByNormalizedUrl(url) match {
        case Some(uri) =>
          val userId = request.userId
          val kept = Bookmark.load(uri, userId).isDefined  // .state == ACTIVE ?
          val following = Follow.get(userId, uri.id.get).map(_.isActive).getOrElse(false)

          val friendIds = SocialConnection.getFortyTwoUserConnections(userId)
          val searcher = inject[URIGraph].getURIGraphSearcher
          val friendEdgeSet = searcher.getUserToUserEdgeSet(userId, friendIds)
          val sharingUserIds = searcher.intersect(friendEdgeSet, searcher.getUriToUserEdgeSet(uri.id.get)).destIdSet - userId
          val socialUsers = sharingUserIds.map(u => UserWithSocial.toUserWithSocial(User.get(u))).toSeq

          val numComments = Comment.getPublicCount(uri.id.get)
          val numMessages = Comment.getMessageCount(uri.id.get, userId)

          (kept, following, socialUsers, numComments, numMessages)
        case None =>
          (false, false, Nil, 0L, 0L)
      }
    }

    Ok(JsObject(Seq(
        "kept" -> JsBoolean(kept),
        "following" -> JsBoolean(following),
        "friends" -> userWithSocialSerializer.writes(socialUsers),
        "numComments" -> JsNumber(numComments),
        "numMessages" -> JsNumber(numMessages))))
  }

  @deprecated("replaced by getSliderInfo, still here for backwards compatibility", "2012-11-26")
  def usersKeptUrl(url: String) = AuthenticatedJsonAction { request =>
    val socialUsers = CX.withConnection { implicit c =>
      NormalizedURI.getByNormalizedUrl(url) match {
        case Some(uri) =>
          val userId = request.userId
          val friendIds = SocialConnection.getFortyTwoUserConnections(userId)

          val searcher = inject[URIGraph].getURIGraphSearcher
          val friendEdgeSet = searcher.getUserToUserEdgeSet(userId, friendIds)
          val sharingUserIds = searcher.intersect(friendEdgeSet, searcher.getUriToUserEdgeSet(uri.id.get)).destIdSet - userId

          sharingUserIds.map(u => UserWithSocial.toUserWithSocial(User.get(u))).toSeq

        case None =>
          Seq[UserWithSocial]()
      }
    }

    Ok(userWithSocialSerializer.writes(socialUsers))
  }

  def getSocialConnections() = AuthenticatedJsonAction { authRequest =>
    val socialConnections = CX.withConnection { implicit c =>
      SocialConnection.getFortyTwoUserConnections(authRequest.userId).map(uid => BasicUser(User.get(uid))).toSeq
    }

    Ok(JsObject(Seq(
      ("friends" -> JsArray(socialConnections.map(sc => BasicUserSerializer.basicUserSerializer.writes(sc))))
    )))
  }

  def getUser(id: Id[User]) = AdminJsonAction { request =>
    val user = CX.withConnection { implicit c =>
      UserWithSocial.toUserWithSocial(User.get(id))
    }
    Ok(userWithSocialSerializer.writes(user))
  }

  def userStatistics(user: User)(implicit conn: Connection): UserStatistics = {
    val socialConnectionCount = SocialConnection.getUserConnectionsCount(user.id.get)
    UserStatistics(user, UserWithSocial.toUserWithSocial(user), socialConnectionCount)
  }

  def userView(userId: Id[User]) = AdminHtmlAction { implicit request =>
    val (user, bookmarks, socialUserInfos, socialConnections, fortyTwoConnections, follows) = CX.withConnection { implicit c =>
      val userWithSocial = UserWithSocial.toUserWithSocial(User.get(userId))
      val bookmarks = Bookmark.ofUser(userWithSocial.user)
      val socialUserInfos = SocialUserInfo.getByUser(userWithSocial.user.id.get)
      val socialConnections = SocialConnection.getUserConnections(userId).sortWith((a,b) => a.fullName < b.fullName)
      val fortyTwoConnections = (SocialConnection.getFortyTwoUserConnections(userId) map (User.get(_)) map UserWithSocial.toUserWithSocial toSeq).sortWith((a,b) => a.socialUserInfo.fullName < b.socialUserInfo.fullName)
      val follows = Follow.getAll(userId) map {u => NormalizedURI.get(u.uriId)}
      (userWithSocial, bookmarks, socialUserInfos, socialConnections, fortyTwoConnections, follows)
    }
    val rawInfos = socialUserInfos map {info =>
      inject[SocialUserRawInfoStore].get(info.id.get)
    }
    Ok(views.html.user(user, bookmarks, socialUserInfos, rawInfos.flatten, socialConnections, fortyTwoConnections, follows))
  }

  def usersView = AdminHtmlAction { implicit request =>
    val users = CX.withConnection { implicit c =>
      User.all map userStatistics
    }
    Ok(views.html.users(users))
  }

  def updateUser(userId: Id[User]) = AdminHtmlAction { implicit request =>
    val form = request.request.body.asFormUrlEncoded match {
      case Some(req) => req.map(r => (r._1 -> r._2.head))
      case None => throw new Exception("whoops")

    }

    // We want to throw an exception (.get) if `emails' was not passed in. As we expand this, we should add Play! form validation
    val emailList = form.get("emails").get.split(",").map(_.toLowerCase().trim()).toList.distinct.map(em => em match {
      case s if s.length > 5 => Some(s)
      case _ => None
    }).flatten

    CX.withConnection { implicit conn =>
      val oldEmails = EmailAddress.getByUser(userId).toSet
      val newEmails = (emailList map { address =>
        val email = EmailAddress.getByAddressOpt(address)
        email match {
          case Some(addr) => addr // We're good! It already exists
          case None => // Create a new one
            log.info("Adding email address %s to userId %s".format(address, userId.toString))
            EmailAddress(address,userId).save
        }
      }).toSet

      // Set state of removed email addresses to INACTIVE
      (oldEmails -- newEmails) map { removedEmail =>
        log.info("Removing email address %s from userId %s".format(removedEmail.address, userId.toString))
        removedEmail.withState(EmailAddress.States.INACTIVE).save
      }
    }

    Redirect(com.keepit.controllers.routes.UserController.userView(userId))
  }

  def addExperiment(userId: Id[User], experimentType: String) = AdminJsonAction { request =>
    val experimants = CX.withConnection { implicit c =>
      val existing = UserExperiment.getByUser(userId)
      val experiment = UserExperiment.ExperimentTypes(experimentType)
      if (existing contains(experimentType)) throw new Exception("user %s already has an experiment %s".format(experimentType))
      UserExperiment(userId = userId, experimentType = experiment).save
      UserExperiment.getByUser(userId)
    }
    Ok(JsArray(experimants map {e => JsString(e.experimentType.value) }))
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
