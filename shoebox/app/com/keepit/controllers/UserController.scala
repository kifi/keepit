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
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
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
import com.keepit.common.social.UserWithSocial.toUserWithSocial
import com.keepit.common.controller.FortyTwoController
import com.keepit.search.index.ArticleIndexer
import com.keepit.search.graph.URIGraph
import views.html.defaultpages.unauthorized
import org.joda.time.LocalDate
import scala.collection.immutable.Map
import play.api.libs.json.JsArray
import com.keepit.common.mail.ElectronicMail
import com.keepit.common.mail._

case class UserStatistics(user: User, userWithSocial: UserWithSocial, socialConnectionCount: Long, kifiInstallations: Seq[KifiInstallation])

object UserController extends FortyTwoController {

  def getSliderInfo(url: String) = AuthenticatedJsonAction { request =>
    val (bookmark, following, socialUsers, numComments, numMessages) = CX.withConnection { implicit c =>
      NormalizedURICxRepo.getByNormalizedUrl(url) match {
        case Some(uri) =>
          val userId = request.userId
          val bookmark = BookmarkCxRepo.getByUriAndUser(uri.id.get, userId).filter(_.isActive)
          val following = FollowCxRepo.get(userId, uri.id.get).filter(_.isActive).isDefined

          val friendIds = SocialConnectionCxRepo.getFortyTwoUserConnections(userId)
          val searcher = inject[URIGraph].getURIGraphSearcher
          val friendEdgeSet = searcher.getUserToUserEdgeSet(userId, friendIds)
          val sharingUserIds = searcher.intersect(friendEdgeSet, searcher.getUriToUserEdgeSet(uri.id.get)).destIdSet - userId
          val socialUsers = sharingUserIds.map(u => UserWithSocial.toUserWithSocial(UserCxRepo.get(u))).toSeq

          val numComments = CommentCxRepo.getPublicCount(uri.id.get)
          val numMessages = CommentCxRepo.getMessageCount(uri.id.get, userId)

          (bookmark, following, socialUsers, numComments, numMessages)
        case None =>
          (None, false, Nil, 0L, 0L)
      }
    }

    Ok(JsObject(Seq(
        "kept" -> JsBoolean(bookmark.isDefined),
        "private" -> JsBoolean(bookmark.map(_.isPrivate).getOrElse(false)),
        "following" -> JsBoolean(following),
        "friends" -> userWithSocialSerializer.writes(socialUsers),
        "numComments" -> JsNumber(numComments),
        "numMessages" -> JsNumber(numMessages))))
  }

  // TODO: delete once no beta users have old plugin using this (replaced by getSliderInfo)
  def usersKeptUrl(url: String) = AuthenticatedJsonAction { request =>
    val socialUsers = CX.withConnection { implicit c =>
      NormalizedURICxRepo.getByNormalizedUrl(url) match {
        case Some(uri) =>
          val userId = request.userId
          val friendIds = SocialConnectionCxRepo.getFortyTwoUserConnections(userId)

          val searcher = inject[URIGraph].getURIGraphSearcher
          val friendEdgeSet = searcher.getUserToUserEdgeSet(userId, friendIds)
          val sharingUserIds = searcher.intersect(friendEdgeSet, searcher.getUriToUserEdgeSet(uri.id.get)).destIdSet - userId

          sharingUserIds.map(u => UserWithSocial.toUserWithSocial(UserCxRepo.get(u))).toSeq

        case None =>
          Seq[UserWithSocial]()
      }
    }

    Ok(userWithSocialSerializer.writes(socialUsers))
  }

  def getSocialConnections() = AuthenticatedJsonAction { authRequest =>
    val socialConnections = CX.withConnection { implicit c =>
      SocialConnectionCxRepo.getFortyTwoUserConnections(authRequest.userId).map(uid => BasicUser(UserCxRepo.get(uid))).toSeq
    }

    Ok(JsObject(Seq(
      ("friends" -> JsArray(socialConnections.map(sc => BasicUserSerializer.basicUserSerializer.writes(sc))))
    )))
  }

  def getUser(id: Id[User]) = AdminJsonAction { request =>
    val user = CX.withConnection { implicit c =>
      UserWithSocial.toUserWithSocial(UserCxRepo.get(id))
    }
    Ok(userWithSocialSerializer.writes(user))
  }

  def userStatistics(user: User)(implicit conn: Connection): UserStatistics = {
    val socialConnectionCount = SocialConnectionCxRepo.getUserConnectionsCount(user.id.get)
    val kifiInstallations = KifiInstallationCxRepo.all(user.id.get).sortWith((a,b) => a.updatedAt.isBefore(b.updatedAt))
    UserStatistics(user, UserWithSocial.toUserWithSocial(user), socialConnectionCount, kifiInstallations)
  }

  def moreUserInfoView(userId: Id[User]) = AdminHtmlAction { implicit request =>
    val (user, socialUserInfos, follows, comments, messages, sentElectronicMails, receivedElectronicMails) = CX.withConnection { implicit conn =>
      val userWithSocial = UserWithSocial.toUserWithSocial(UserCxRepo.get(userId))
      val socialUserInfos = SocialUserInfoCxRepo.getByUser(userWithSocial.user.id.get)
      val follows = FollowCxRepo.all(userId) map {f => NormalizedURICxRepo.get(f.uriId)}
      val comments = CommentCxRepo.all(CommentPermissions.PUBLIC, userId) map {c =>
        (NormalizedURICxRepo.get(c.uriId), c)
      }
      val messages = CommentCxRepo.all(CommentPermissions.MESSAGE, userId) map {c =>
        (NormalizedURICxRepo.get(c.uriId), c, CommentRecipientCxRepo.getByComment(c.id.get) map { r => toUserWithSocial(UserCxRepo.get(r.userId.get)) })
      }
      val sentElectronicMails = ElectronicMail.forSender(userId);
      val mailAddresses = UserWithSocial.toUserWithSocial(UserCxRepo.get(userId)).emails.map(_.address)
      val receivedElectronicMails = ElectronicMail.forRecipient(mailAddresses);
      (userWithSocial, socialUserInfos, follows, comments, messages, sentElectronicMails, receivedElectronicMails)
    }
    val rawInfos = socialUserInfos map {info =>
      inject[SocialUserRawInfoStore].get(info.id.get)
    }
    Ok(views.html.moreUserInfo(user, rawInfos.flatten, socialUserInfos, follows, comments, messages, sentElectronicMails, receivedElectronicMails))
  }

  def userView(userId: Id[User]) = AdminHtmlAction { implicit request =>
    val (user, bookmarks, socialConnections, fortyTwoConnections, kifiInstallations) = CX.withConnection { implicit conn =>
      val userWithSocial = UserWithSocial.toUserWithSocial(UserCxRepo.get(userId))
      val bookmarks = BookmarkCxRepo.ofUser(userWithSocial.user)
      val socialConnections = SocialConnectionCxRepo.getUserConnections(userId).sortWith((a,b) => a.fullName < b.fullName)
      val fortyTwoConnections = (SocialConnectionCxRepo.getFortyTwoUserConnections(userId) map (UserCxRepo.get(_)) map UserWithSocial.toUserWithSocial toSeq).sortWith((a,b) => a.socialUserInfo.fullName < b.socialUserInfo.fullName)
      val kifiInstallations = KifiInstallationCxRepo.all(userId).sortWith((a,b) => a.updatedAt.isBefore(b.updatedAt))
      (userWithSocial, bookmarks, socialConnections, fortyTwoConnections, kifiInstallations)
    }
    Ok(views.html.user(user, bookmarks, socialConnections, fortyTwoConnections, kifiInstallations))
  }

  def usersView = AdminHtmlAction { implicit request =>
    val users = CX.withConnection { implicit c =>
      UserCxRepo.all map userStatistics
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

    inject[DBConnection].readWrite{ implicit session =>
      val emailRepo = inject[EmailAddressRepo]
      val oldEmails = emailRepo.getByUser(userId).toSet
      val newEmails = (emailList map { address =>
        val email = emailRepo.getByAddressOpt(address)
        email match {
          case Some(addr) => addr // We're good! It already exists
          case None => // Create a new one
            log.info("Adding email address %s to userId %s".format(address, userId.toString))
            emailRepo.save(EmailAddress(address = address, userId = userId))
        }
      }).toSet

      // Set state of removed email addresses to INACTIVE
      (oldEmails -- newEmails) map { removedEmail =>
        log.info("Removing email address %s from userId %s".format(removedEmail.address, userId.toString))
        emailRepo.save(removedEmail.withState(EmailAddressStates.INACTIVE))
      }
    }

    Redirect(com.keepit.controllers.routes.UserController.userView(userId))
  }

  def addExperiment(userId: Id[User], experimentType: String) = AdminJsonAction { request =>
    val repo = inject[UserExperimentRepo]
    val experimants = inject[DBConnection].readWrite{ implicit session =>
      val existing = repo.getByUser(userId)
      val experiment = ExperimentTypes(experimentType)
      if (existing contains(experimentType)) throw new Exception("user %s already has an experiment %s".format(experimentType))
      repo.save(UserExperiment(userId = userId, experimentType = experiment))
      repo.getByUser(userId)
    }
    Ok(JsArray(experimants map {e => JsString(e.experimentType.value) }))
  }

  def refreshAllSocialInfo(userId: Id[User]) = AdminHtmlAction { implicit request =>
    val socialUserInfos = CX.withConnection { implicit c =>
      val user = UserCxRepo.get(userId)
      SocialUserInfoCxRepo.getByUser(user.id.get)
    }
    val graph = inject[SocialGraphPlugin]
    socialUserInfos foreach { info =>
      graph.asyncFetch(info)
    }
    Redirect(com.keepit.controllers.routes.UserController.userView(userId))
  }
}
