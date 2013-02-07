package com.keepit.controllers

import com.keepit.inject._
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import com.keepit.model._
import com.keepit.serializer.UserWithSocialSerializer._
import com.keepit.serializer.BasicUserSerializer
import com.keepit.common.social._
import com.keepit.common.controller.FortyTwoController
import com.keepit.search.graph.URIGraph
import com.keepit.search.Lang
import com.keepit.search.MainSearcherFactory
import com.keepit.common.mail._

import akka.dispatch.Await
import akka.util.duration._
import play.api.Play.current
import play.api.libs.json.{JsArray, JsBoolean, JsNumber, JsObject, JsString}

case class UserStatistics(user: User, userWithSocial: UserWithSocial, kifiInstallations: Seq[KifiInstallation])

object UserController extends FortyTwoController {

  def getSliderInfo(url: String) = AuthenticatedJsonAction { request =>
    val (bookmark, following, socialUsers, numComments, numMessages) = inject[DBConnection].readOnly {implicit s =>
      inject[NormalizedURIRepo].getByNormalizedUrl(url) match {
        case Some(uri) =>
          val userId = request.userId
          val bookmark = inject[BookmarkRepo].getByUriAndUser(uri.id.get, userId).filter(_.isActive)
          val following = inject[FollowRepo].get(userId, uri.id.get).isDefined

          val friendIds = inject[SocialConnectionRepo].getFortyTwoUserConnections(userId)
          val searcher = inject[URIGraph].getURIGraphSearcher
          val friendEdgeSet = searcher.getUserToUserEdgeSet(userId, friendIds)
          val sharingUserIds = searcher.intersect(friendEdgeSet, searcher.getUriToUserEdgeSet(uri.id.get)).destIdSet - userId
          val socialUsers = sharingUserIds.map(u => inject[UserWithSocialRepo].toUserWithSocial(inject[UserRepo].get(u))).toSeq

          val commentRepo = inject[CommentRepo]
          val numComments = commentRepo.getPublicCount(uri.id.get)
          val numMessages = commentRepo.getMessages(uri.id.get, userId).size

          (bookmark, following, socialUsers, numComments, numMessages)
        case None =>
          (None, false, Nil, 0, 0)
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
    val socialUsers = inject[DBConnection].readOnly {implicit s =>
      inject[NormalizedURIRepo].getByNormalizedUrl(url) match {
        case Some(uri) =>
          val userId = request.userId
          val friendIds = inject[SocialConnectionRepo].getFortyTwoUserConnections(userId)

          val searcher = inject[URIGraph].getURIGraphSearcher
          val friendEdgeSet = searcher.getUserToUserEdgeSet(userId, friendIds)
          val sharingUserIds = searcher.intersect(friendEdgeSet, searcher.getUriToUserEdgeSet(uri.id.get)).destIdSet - userId

          sharingUserIds.map(u => inject[UserWithSocialRepo].toUserWithSocial(inject[UserRepo].get(u))).toSeq

        case None =>
          Seq[UserWithSocial]()
      }
    }

    Ok(userWithSocialSerializer.writes(socialUsers))
  }

  def getSocialConnections() = AuthenticatedJsonAction { authRequest =>
    val socialConnections = inject[DBConnection].readOnly {implicit s =>
      val userRepo = inject[UserRepo]
      val basicUserRepo = inject[BasicUserRepo]
      inject[SocialConnectionRepo].getFortyTwoUserConnections(authRequest.userId).map(uid => basicUserRepo.load(userRepo.get(uid))).toSeq
    }

    Ok(JsObject(Seq(
      ("friends" -> JsArray(socialConnections.map(sc => BasicUserSerializer.basicUserSerializer.writes(sc))))
    )))
  }

  def getUser(id: Id[User]) = AdminJsonAction { request =>
    val user = inject[DBConnection].readOnly { implicit s =>
      val repo = inject[UserWithSocialRepo]
      repo.toUserWithSocial(inject[UserRepo].get(id))
    }
    Ok(userWithSocialSerializer.writes(user))
  }

  def userStatistics(user: User)(implicit s: RSession): UserStatistics = {
    val kifiInstallations = inject[KifiInstallationRepo].all(user.id.get).sortWith((a,b) => b.updatedAt.isBefore(a.updatedAt)).take(3)
    UserStatistics(user, inject[UserWithSocialRepo].toUserWithSocial(user), kifiInstallations)
  }

  def moreUserInfoView(userId: Id[User]) = AdminHtmlAction { implicit request =>
    val (user, socialUserInfos, follows, comments, messages, sentElectronicMails, receivedElectronicMails) = inject[DBConnection].readOnly { implicit s =>
      val userWithSocialRepo = inject[UserWithSocialRepo]
      val userRepo = inject[UserRepo]
      val socialUserInfoRepo = inject[SocialUserInfoRepo]
      val followRepo = inject[FollowRepo]
      val normalizedURIRepo = inject[NormalizedURIRepo]
      val commentRepo = inject[CommentRepo]
      val mailRepo = inject[ElectronicMailRepo]
      val userWithSocial = userWithSocialRepo.toUserWithSocial(userRepo.get(userId))
      val socialUserInfos = socialUserInfoRepo.getByUser(userWithSocial.user.id.get)
      val follows = followRepo.getByUser(userId) map {f => normalizedURIRepo.get(f.uriId)}
      val comments = commentRepo.all(CommentPermissions.PUBLIC, userId) map {c =>
        (normalizedURIRepo.get(c.uriId), c)
      }
      val messages = commentRepo.all(CommentPermissions.MESSAGE, userId) map {c =>
        (normalizedURIRepo.get(c.uriId), c, inject[CommentRecipientRepo].getByComment(c.id.get) map {
          r => userWithSocialRepo.toUserWithSocial(userRepo.get(r.userId.get))
        })
      }
      val sentElectronicMails = mailRepo.forSender(userId);
      val mailAddresses = userWithSocialRepo.toUserWithSocial(userRepo.get(userId)).emails.map(_.address)
      val receivedElectronicMails = mailRepo.forRecipient(mailAddresses);
      (userWithSocial, socialUserInfos, follows, comments, messages, sentElectronicMails, receivedElectronicMails)
    }
    val rawInfos = socialUserInfos map {info =>
      inject[SocialUserRawInfoStore].get(info.id.get)
    }
    Ok(views.html.moreUserInfo(user, rawInfos.flatten, socialUserInfos, follows, comments, messages, sentElectronicMails, receivedElectronicMails))
  }

  def userView(userId: Id[User]) = AdminHtmlAction { implicit request =>
    val (user, bookmarks, socialConnections, fortyTwoConnections, kifiInstallations) = inject[DBConnection].readOnly {implicit s =>
      val userWithSocial = inject[UserWithSocialRepo].toUserWithSocial(inject[UserRepo].get(userId))
      val bookmarks = inject[BookmarkRepo].getByUser(userId)
      val normalizedURIRepo = inject[NormalizedURIRepo]
      val uris = bookmarks map (_.uriId) map normalizedURIRepo.get
      val socialConnections = inject[SocialConnectionRepo].getUserConnections(userId).sortWith((a,b) => a.fullName < b.fullName)
      val fortyTwoConnections = (inject[SocialConnectionRepo].getFortyTwoUserConnections(userId) map (inject[UserRepo].get(_)) map inject[UserWithSocialRepo].toUserWithSocial toSeq).sortWith((a,b) => a.socialUserInfo.fullName < b.socialUserInfo.fullName)
      val kifiInstallations = inject[KifiInstallationRepo].all(userId).sortWith((a,b) => a.updatedAt.isBefore(b.updatedAt))
      (userWithSocial, (bookmarks, uris).zipped.toList.seq, socialConnections, fortyTwoConnections, kifiInstallations)
    }
    // above needs slicking.
    val historyUpdateCount = inject[DBConnection].readOnly { implicit session =>
      inject[BrowsingHistoryRepo].getByUserId(userId).map(_.updatesCount).getOrElse(0)
    }

    val form = request.request.body.asFormUrlEncoded.map{ req => req.map(r => (r._1 -> r._2.head)) }

    val bookmarkSearch = form.flatMap{ _.get("bookmarkSearch") }
    val filteredBookmarks = bookmarkSearch.map{ query =>
      if (query.trim.length == 0) bookmarks
      else {
        val searcherFactory = inject[MainSearcherFactory]
        val searcher = searcherFactory.bookmarkSearcher(userId)
        val uris = searcher.search(query, Lang("en"))
        bookmarks.filter{ case (b, u) => uris.contains(u.id.get.id) }
      }
    }

    Ok(views.html.user(user, bookmarks.size, filteredBookmarks.getOrElse(bookmarks), socialConnections, fortyTwoConnections, kifiInstallations, historyUpdateCount, bookmarkSearch))
  }

  def usersView = AdminHtmlAction { implicit request =>
    val users = inject[DBConnection].readOnly { implicit s =>
      inject[UserRepo].all map userStatistics
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
    val experiments = inject[DBConnection].readWrite{ implicit session =>
      val existing = repo.getByUser(userId)
      val experiment = ExperimentTypes(experimentType)
      if (existing contains(experimentType)) throw new Exception("user %s already has an experiment %s".format(experimentType))
      repo.save(UserExperiment(userId = userId, experimentType = experiment))
      repo.getByUser(userId)
    }
    Ok(JsArray(experiments map {e => JsString(e.experimentType.value) }))
  }

  def refreshAllSocialInfo(userId: Id[User]) = AdminHtmlAction { implicit request =>
    val socialUserInfos = inject[DBConnection].readOnly {implicit s =>
      val user = inject[UserRepo].get(userId)
      inject[SocialUserInfoRepo].getByUser(user.id.get)
    }
    val graph = inject[SocialGraphPlugin]
    socialUserInfos foreach { info =>
      Await.result(graph.asyncFetch(info), 5 minutes)
    }
    Redirect(com.keepit.controllers.routes.UserController.userView(userId))
  }
}
