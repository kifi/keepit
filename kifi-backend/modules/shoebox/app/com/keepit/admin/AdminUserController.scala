package com.keepit.controllers.admin

import scala.concurrent.Await
import scala.concurrent.duration._

import com.google.inject.{Inject, Singleton}
import com.keepit.common.controller.{AdminController, ActionAuthenticator}
import com.keepit.common.db._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.db.slick._
import com.keepit.common.mail._
import com.keepit.common.social._
import com.keepit.common.time._
import com.keepit.model._
import com.keepit.realtime.UserChannel
import com.keepit.search.SearchServiceClient

import play.api.data.Forms._
import play.api.data._
import play.api.libs.json.Json
import views.html

case class UserStatistics(
    user: User,
    socialUsers: Seq[SocialUserInfo],
    bookmarksCount: Int,
    experiments: Set[State[ExperimentType]],
    kifiInstallations: Seq[KifiInstallation])

@Singleton
class AdminUserController @Inject() (
    actionAuthenticator: ActionAuthenticator,
    db: Database,
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
    collectionRepo: CollectionRepo,
    keepToCollectionRepo: KeepToCollectionRepo,
    clock: Clock) extends AdminController(actionAuthenticator) {

  def moreUserInfoView(userId: Id[User]) = AdminHtmlAction { implicit request =>
    val (user, socialUserInfos, follows, comments, messages, sentElectronicMails) = db.readOnly { implicit s =>
      val user = userRepo.get(userId)
      val socialUserInfos = socialUserInfoRepo.getByUser(user.id.get)
      val follows = followRepo.getByUser(userId) map {f => normalizedURIRepo.get(f.uriId)}
      val comments = commentRepo.all(CommentPermissions.PUBLIC, userId) map {c =>
        (normalizedURIRepo.get(c.uriId), c)
      }
      val messages = commentRepo.all(CommentPermissions.MESSAGE, userId) map {c =>
        (normalizedURIRepo.get(c.uriId), c, commentRecipientRepo.getByComment(c.id.get) map {
          r => userRepo.get(r.userId.get)
        })
      }
      val sentElectronicMails = mailRepo.forSender(userId)
      (user, socialUserInfos, follows, comments, messages, sentElectronicMails)
    }
    val rawInfos = socialUserInfos map {info =>
      socialUserRawInfoStore.get(info.id.get)
    }
    Ok(html.admin.moreUserInfo(user, rawInfos.flatten, socialUserInfos, follows, comments, messages, sentElectronicMails))
  }

  def updateCollectionsForBookmark(id: Id[Bookmark]) = AdminHtmlAction { implicit request =>
    request.request.body.asFormUrlEncoded.map { _.map(r => (r._1 -> r._2.head)) }.map { map =>
      val collectionNames = map.get("collections").getOrElse("").split(",").map(_.trim).filterNot(_.isEmpty)
      val collections = db.readWrite { implicit s =>
        val bookmark = bookmarkRepo.get(id)
        val userId = bookmark.userId
        val existing = keepToCollectionRepo.getByBookmark(id, excludeState = None).map(k => k.collectionId -> k).toMap
        val colls = collectionNames.map { name =>
          val collection = collectionRepo.getByUserAndName(userId, name, excludeState = None) match {
            case Some(coll) if coll.isActive => coll
            case Some(coll) => collectionRepo.save(coll.copy(state = CollectionStates.ACTIVE))
            case None => collectionRepo.save(Collection(userId = userId, name = name))
          }
          existing.get(collection.id.get) match {
            case Some(ktc) if ktc.isActive => ktc
            case Some(ktc) => keepToCollectionRepo.save(ktc.copy(state = KeepToCollectionStates.ACTIVE))
            case None => keepToCollectionRepo.save(KeepToCollection(bookmarkId = id, collectionId = collection.id.get))
          }
          collection
        }
        (existing -- colls.map(_.id.get)).values.foreach { ktc =>
          keepToCollectionRepo.save(ktc.copy(state = KeepToCollectionStates.INACTIVE))
        }
        colls.map(_.name)
      }
      Ok(Json.obj("collections" -> collections))
    } getOrElse BadRequest
  }

  def userView(userId: Id[User]) = AdminHtmlAction { implicit request =>
    val (user, bookmarks, socialUsers, socialConnections, fortyTwoConnections, kifiInstallations, allowedInvites, emails) = db.readOnly {implicit s =>
      val user = userRepo.get(userId)
      val bookmarks = bookmarkRepo.getByUser(userId)
      val uris = bookmarks map (_.uriId) map normalizedURIRepo.get
      val socialUsers = socialUserInfoRepo.getByUser(userId)
      val socialConnections = socialConnectionRepo.getUserConnections(userId).sortWith((a,b) => a.fullName < b.fullName)
      val fortyTwoConnections = userConnectionRepo.getConnectedUsers(userId).map { userId =>
        userRepo.get(userId)
      }.toSeq.sortBy(u => "${u.firstName} ${u.lastName}")
      val kifiInstallations = kifiInstallationRepo.all(userId).sortWith((a,b) => a.updatedAt.isBefore(b.updatedAt))
      val allowedInvites = userValueRepo.getValue(request.user.id.get, "availableInvites").getOrElse("6").toInt
      val emails = emailRepo.getByUser(user.id.get)
      (user, (bookmarks, uris).zipped.toList.seq, socialUsers, socialConnections, fortyTwoConnections, kifiInstallations, allowedInvites, emails)
    }
    // above needs slicking.
    val historyUpdateCount = db.readOnly { implicit session =>
      browsingHistoryRepo.getByUserId(userId).map(_.updatesCount).getOrElse(0)
    }

    val form = request.request.body.asFormUrlEncoded.map{ req => req.map(r => (r._1 -> r._2.head)) }

    val bookmarkSearch = form.flatMap{ _.get("bookmarkSearch") }
    val collectionFilter = form.flatMap(_.get("collectionFilter")).collect {
      case cid if cid.toLong > 0 => Id[Collection](cid.toLong)
    }
    val bookmarkFilter = collectionFilter.map { collId =>
      db.readOnly { implicit s => keepToCollectionRepo.getBookmarksInCollection(collId) }
    }
    val filteredBookmarks = db.readOnly { implicit s =>
      val query = bookmarkSearch.getOrElse("")
      (if (query.trim.length == 0) {
        bookmarks
      } else {
        val uris = Await.result(searchClient.searchKeeps(userId, query), Duration.Inf)
        bookmarks.filter{ case (b, u) => uris.contains(u.id.get) }
      }) collect {
        case (mark, uri) if bookmarkFilter.isEmpty || bookmarkFilter.get.contains(mark.id.get) =>
          val colls = keepToCollectionRepo.getCollectionsForBookmark(mark.id.get).map(collectionRepo.get).map(_.name)
          (mark, uri, colls)
      }
    }
    val collections = db.readOnly { implicit s => collectionRepo.getByUser(userId) }
    val experiments = db.readOnly { implicit s => userExperimentRepo.getUserExperiments(user.id.get) }

    Ok(html.admin.user(user, bookmarks.size, experiments, filteredBookmarks, socialUsers, socialConnections,
      fortyTwoConnections, kifiInstallations, historyUpdateCount, bookmarkSearch, allowedInvites, emails,
      collections, collectionFilter))
  }

  def allUsersView = usersView(0)

  def usersView(page: Int = 0) = AdminHtmlAction { implicit request =>
    def userStatistics(user: User)(implicit s: RSession): UserStatistics = {
      val kifiInstallations = kifiInstallationRepo.all(user.id.get).sortWith((a,b) => b.updatedAt.isBefore(a.updatedAt)).take(3)
      UserStatistics(user,
        socialUserInfoRepo.getByUser(user.id.get),
        bookmarkRepo.getCountByUser(user.id.get),
        userExperimentRepo.getUserExperiments(user.id.get),
        kifiInstallations)
    }

    val PAGE_SIZE = 200

    val users = db.readOnly { implicit s =>
      userRepo.pageExcluding(UserStates.PENDING, UserStates.INACTIVE, UserStates.BLOCKED)(page, PAGE_SIZE) map userStatistics
    }
    val userCount = db.readOnly { implicit s => userRepo.countExcluding(UserStates.PENDING, UserStates.INACTIVE, UserStates.BLOCKED) }
    Ok(html.admin.users(users, page, userCount, Math.ceil(userCount.toFloat / PAGE_SIZE.toFloat).toInt))
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

    Redirect(routes.AdminUserController.usersView(0))
  }
}
