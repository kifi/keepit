package com.keepit.controllers.admin

import com.google.inject.{Inject, Singleton}
import com.keepit.common.controller.AdminController
import com.keepit.common.db._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.db.slick._
import com.keepit.common.mail._
import com.keepit.common.social._
import com.keepit.model._
import com.keepit.search.SearchServiceClient
import play.api.libs.json.Json
import scala.concurrent.Await
import scala.concurrent.duration._
import views.html

case class UserStatistics(user: User, userWithSocial: UserWithSocial, kifiInstallations: Seq[KifiInstallation])

@Singleton
class AdminUserController @Inject() (
    db: Database,
    userWithSocialRepo: UserWithSocialRepo,
    userRepo: UserRepo,
    socialUserInfoRepo: SocialUserInfoRepo,
    followRepo: FollowRepo,
    normalizedURIRepo: NormalizedURIRepo,
    commentRepo: CommentRepo,
    mailRepo: ElectronicMailRepo,
    commentRecipientRepo: CommentRecipientRepo,
    socialUserRawInfoStore: SocialUserRawInfoStore,
    bookmarkRepo: BookmarkRepo,
    socialConnectionRepo: SocialConnectionRepo,
    kifiInstallationRepo: KifiInstallationRepo,
    browsingHistoryRepo: BrowsingHistoryRepo,
    emailRepo: EmailAddressRepo,
    userExperimentRepo: UserExperimentRepo,
    socialGraphPlugin: SocialGraphPlugin,
    searchClient: SearchServiceClient
  ) extends AdminController {

  def moreUserInfoView(userId: Id[User]) = AdminHtmlAction { implicit request =>
    val (user, socialUserInfos, follows, comments, messages, sentElectronicMails, receivedElectronicMails) = db.readOnly { implicit s =>
      val userWithSocial = userWithSocialRepo.toUserWithSocial(userRepo.get(userId))
      val socialUserInfos = socialUserInfoRepo.getByUser(userWithSocial.user.id.get)
      val follows = followRepo.getByUser(userId) map {f => normalizedURIRepo.get(f.uriId)}
      val comments = commentRepo.all(CommentPermissions.PUBLIC, userId) map {c =>
        (normalizedURIRepo.get(c.uriId), c)
      }
      val messages = commentRepo.all(CommentPermissions.MESSAGE, userId) map {c =>
        (normalizedURIRepo.get(c.uriId), c, commentRecipientRepo.getByComment(c.id.get) map {
          r => userWithSocialRepo.toUserWithSocial(userRepo.get(r.userId.get))
        })
      }
      val sentElectronicMails = mailRepo.forSender(userId);
      val mailAddresses = userWithSocialRepo.toUserWithSocial(userRepo.get(userId)).emails.map(_.address)
      val receivedElectronicMails = mailRepo.forRecipient(mailAddresses);
      (userWithSocial, socialUserInfos, follows, comments, messages, sentElectronicMails, receivedElectronicMails)
    }
    val rawInfos = socialUserInfos map {info =>
      socialUserRawInfoStore.get(info.id.get)
    }
    Ok(html.admin.moreUserInfo(user, rawInfos.flatten, socialUserInfos, follows, comments, messages, sentElectronicMails, receivedElectronicMails))
  }

  def userView(userId: Id[User]) = AdminHtmlAction { implicit request =>
    val (user, bookmarks, socialConnections, fortyTwoConnections, kifiInstallations) = db.readOnly {implicit s =>
      val userWithSocial = userWithSocialRepo.toUserWithSocial(userRepo.get(userId))
      val bookmarks = bookmarkRepo.getByUser(userId)
      val uris = bookmarks map (_.uriId) map normalizedURIRepo.get
      val socialConnections = socialConnectionRepo.getUserConnections(userId).sortWith((a,b) => a.fullName < b.fullName)
      val fortyTwoConnections = (socialConnectionRepo.getFortyTwoUserConnections(userId) map (userRepo.get(_)) map userWithSocialRepo.toUserWithSocial toSeq).sortWith((a,b) => a.socialUserInfo.fullName < b.socialUserInfo.fullName)
      val kifiInstallations = kifiInstallationRepo.all(userId).sortWith((a,b) => a.updatedAt.isBefore(b.updatedAt))
      (userWithSocial, (bookmarks, uris).zipped.toList.seq, socialConnections, fortyTwoConnections, kifiInstallations)
    }
    // above needs slicking.
    val historyUpdateCount = db.readOnly { implicit session =>
      browsingHistoryRepo.getByUserId(userId).map(_.updatesCount).getOrElse(0)
    }

    val form = request.request.body.asFormUrlEncoded.map{ req => req.map(r => (r._1 -> r._2.head)) }

    val bookmarkSearch = form.flatMap{ _.get("bookmarkSearch") }
    val filteredBookmarks = bookmarkSearch.map{ query =>
      if (query.trim.length == 0) bookmarks
      else {
        val uris = Await.result(searchClient.searchKeeps(userId, query), 1 second)
        bookmarks.filter{ case (b, u) => uris.contains(u.id.get) }
      }
    }

    Ok(html.admin.user(user, bookmarks.size, filteredBookmarks.getOrElse(bookmarks), socialConnections, fortyTwoConnections, kifiInstallations, historyUpdateCount, bookmarkSearch))
  }

  def usersView = AdminHtmlAction { implicit request =>
    def userStatistics(user: User)(implicit s: RSession): UserStatistics = {
      val kifiInstallations = kifiInstallationRepo.all(user.id.get).sortWith((a,b) => b.updatedAt.isBefore(a.updatedAt)).take(3)
      UserStatistics(user, userWithSocialRepo.toUserWithSocial(user), kifiInstallations)
    }

    val users = db.readOnly { implicit s =>
      userRepo.all map userStatistics
    }
    Ok(html.admin.users(users))
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

    db.readWrite{ implicit session =>
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

    Redirect(com.keepit.controllers.admin.routes.AdminUserController.userView(userId))
  }

  def addExperiment(userId: Id[User], experiment: String) = AdminJsonAction { request =>
    val expType = ExperimentTypes(experiment)
    db.readWrite { implicit session =>
      userExperimentRepo.get(userId, expType, excludeState = None) match {
        case Some(ue) if ue.isActive => ue
        case Some(ue) => userExperimentRepo.save(ue.withState(UserExperimentStates.ACTIVE))
        case None => userExperimentRepo.save(UserExperiment(userId = userId, experimentType = expType))
      }
    }
    Ok(Json.obj(experiment -> true))
  }

  def removeExperiment(userId: Id[User], experiment: String) = AdminJsonAction { request =>
    db.readWrite { implicit session =>
      userExperimentRepo.get(userId, ExperimentTypes(experiment)).foreach { ue =>
        userExperimentRepo.save(ue.withState(UserExperimentStates.INACTIVE))
      }
    }
    Ok(Json.obj(experiment -> false))
  }

  def refreshAllSocialInfo(userId: Id[User]) = AdminHtmlAction { implicit request =>
    val socialUserInfos = db.readOnly {implicit s =>
      val user = userRepo.get(userId)
      socialUserInfoRepo.getByUser(user.id.get)
    }
    socialUserInfos foreach { info =>
      Await.result(socialGraphPlugin.asyncFetch(info), 5 minutes)
    }
    Redirect(com.keepit.controllers.admin.routes.AdminUserController.userView(userId))
  }
}
