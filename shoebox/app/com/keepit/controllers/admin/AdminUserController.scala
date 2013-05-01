package com.keepit.controllers.admin

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
import play.api.data._
import play.api.data.Forms._
import com.keepit.realtime.UserChannel
import com.keepit.common.time._

import com.google.inject.{Inject, Singleton}
import com.keepit.common.controller.{AdminController, ActionAuthenticator}

case class UserStatistics(user: User, userWithSocial: UserWithSocial, kifiInstallations: Seq[KifiInstallation])

@Singleton
class AdminUserController @Inject() (
    actionAuthenticator: ActionAuthenticator,
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
    userConnectionRepo: UserConnectionRepo,
    kifiInstallationRepo: KifiInstallationRepo,
    browsingHistoryRepo: BrowsingHistoryRepo,
    emailRepo: EmailAddressRepo,
    userExperimentRepo: UserExperimentRepo,
    socialGraphPlugin: SocialGraphPlugin,
    searchClient: SearchServiceClient,
    userChannel: UserChannel,
    userValueRepo: UserValueRepo,
    clock: Clock) extends AdminController(actionAuthenticator) {

  def moreUserInfoView(userId: Id[User]) = AdminHtmlAction { implicit request =>
    val (user, socialUserInfos, follows, comments, messages, sentElectronicMails, receivedElectronicMails) = db.readOnly { implicit s =>
      val user = userRepo.get(userId)
      val userWithSocial = userWithSocialRepo.toUserWithSocial(user)
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
      val sentElectronicMails = mailRepo.forSender(userId)
      val emails = emailRepo.getByUser(userId)
      val mailAddresses = emails.map(_.address)
      val receivedElectronicMails = mailRepo.forRecipient(mailAddresses)
      (userWithSocial, socialUserInfos, follows, comments, messages, sentElectronicMails, receivedElectronicMails)
    }
    val rawInfos = socialUserInfos map {info =>
      socialUserRawInfoStore.get(info.id.get)
    }
    Ok(html.admin.moreUserInfo(user, rawInfos.flatten, socialUserInfos, follows, comments, messages, sentElectronicMails, receivedElectronicMails))
  }

  def userView(userId: Id[User]) = AdminHtmlAction { implicit request =>
    val (user, bookmarks, socialConnections, fortyTwoConnections, kifiInstallations, allowedInvites, emails) = db.readOnly {implicit s =>
      val user = userRepo.get(userId)
      val userWithSocial = userWithSocialRepo.toUserWithSocial(user)
      val bookmarks = bookmarkRepo.getByUser(userId)
      val uris = bookmarks map (_.uriId) map normalizedURIRepo.get
      val socialConnections = socialConnectionRepo.getUserConnections(userId).sortWith((a,b) => a.fullName < b.fullName)
      val fortyTwoConnections = userConnectionRepo.getConnectedUsers(userId).map { userId =>
        userWithSocialRepo.toUserWithSocial(userRepo.get(userId))
      }.toSeq.sortBy(_.socialUserInfo.fullName)
      val kifiInstallations = kifiInstallationRepo.all(userId).sortWith((a,b) => a.updatedAt.isBefore(b.updatedAt))
      val allowedInvites = userValueRepo.getValue(request.user.id.get, "availableInvites").getOrElse("6").toInt
      val emails = emailRepo.getByUser(user.id.get)
      (userWithSocial, (bookmarks, uris).zipped.toList.seq, socialConnections, fortyTwoConnections, kifiInstallations, allowedInvites, emails)
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
        val uris = Await.result(searchClient.searchKeeps(userId, query), Duration.Inf)
        bookmarks.filter{ case (b, u) => uris.contains(u.id.get) }
      }
    }

    Ok(html.admin.user(user, bookmarks.size, filteredBookmarks.getOrElse(bookmarks), socialConnections, fortyTwoConnections, kifiInstallations, historyUpdateCount, bookmarkSearch, allowedInvites, emails))
  }

  def usersView = AdminHtmlAction { implicit request =>
    def userStatistics(user: User)(implicit s: RSession): UserStatistics = {
      val kifiInstallations = kifiInstallationRepo.all(user.id.get).sortWith((a,b) => b.updatedAt.isBefore(a.updatedAt)).take(3)
      UserStatistics(user, userWithSocialRepo.toUserWithSocial(user), kifiInstallations)
    }

    val users = db.readOnly { implicit s =>
      userRepo.allExcluding(UserStates.PENDING).map(userStatistics)
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

  def setInvitesCount(userId: Id[User]) = AdminHtmlAction { implicit request =>
    val count = request.request.body.asFormUrlEncoded.get("allowedInvites").headOption.getOrElse("6")
    db.readWrite{ implicit session =>
      userValueRepo.setValue(userId, "availableInvites", count)
    }
    Redirect(routes.AdminUserController.userView(userId))
  }

  def addExperiment(userId: Id[User], experiment: String) = AdminJsonAction { request =>
    val expType = ExperimentTypes(experiment)
    db.readWrite { implicit session =>
      (userExperimentRepo.get(userId, expType, excludeState = None) match {
        case Some(ue) if ue.isActive => None
        case Some(ue) => Some(userExperimentRepo.save(ue.withState(UserExperimentStates.ACTIVE)))
        case None => Some(userExperimentRepo.save(UserExperiment(userId = userId, experimentType = expType)))
      }) foreach { _ =>
        userChannel.push(userId, Json.arr("experiments", userExperimentRepo.getUserExperiments(userId).map(_.value)))
      }
    }
    Ok(Json.obj(experiment -> true))
  }

  def changeState(userId: Id[User], state: String) = AdminJsonAction { request =>
    val userState = state match {
      case UserStates.ACTIVE.value => UserStates.ACTIVE
      case UserStates.INACTIVE.value => UserStates.INACTIVE
      case UserStates.BLOCKED.value => UserStates.BLOCKED
      case UserStates.PENDING.value => UserStates.PENDING
    }

    db.readWrite(implicit s => userRepo.save(userRepo.get(userId).withState(userState)))
    Ok
  }

  def removeExperiment(userId: Id[User], experiment: String) = AdminJsonAction { request =>
    db.readWrite { implicit session =>
      userExperimentRepo.get(userId, ExperimentTypes(experiment)).foreach { ue =>
        userExperimentRepo.save(ue.withState(UserExperimentStates.INACTIVE))
        userChannel.push(userId, Json.arr("experiments", userExperimentRepo.getUserExperiments(userId).map(_.value)))
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

  def sendNotificationToAllUsers() = AdminHtmlAction { implicit request =>
    implicit val playRequest = request.request
    val notifyForm = Form(tuple(
      "title" -> text,
      "bodyHtml" -> text,
      "linkText" -> text,
      "url" -> text,
      "image" -> text,
      "sticky" -> optional(text)
    ))

    val (title, bodyHtml, linkText, url, image, sticky) = notifyForm.bindFromRequest.get

    val json = Json.arr(
      "notify", Json.obj(
        "createdAt" -> clock.now(),
        "category" -> "server_generated",
        "details" -> Json.obj(
          "title" -> title,
          "bodyHtml" -> bodyHtml,
          "linkText" -> linkText,
          "image" -> image,
          "sticky" -> sticky,
          "url" -> url
        )
      )
    )

    userChannel.broadcast(json)

    Redirect(routes.AdminUserController.usersView())
  }
}
