package com.keepit.controllers.admin

import scala.concurrent.{Future, Await}
import scala.concurrent.duration._

import com.google.inject.Inject
import com.keepit.common.controller.{AdminController, ActionAuthenticator}
import com.keepit.common.db._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.db.slick._
import com.keepit.common.mail._
import com.keepit.common.time._
import com.keepit.eliza.ElizaServiceClient
import com.keepit.model._
import com.keepit.search.SearchServiceClient
import com.keepit.social.{SocialGraphPlugin, SocialUserRawInfoStore}

import play.api.data.Forms._
import play.api.data._
import play.api.libs.json._
import views.html
import com.keepit.abook.ABookServiceClient
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.common.service.ServiceType
import java.math.BigInteger
import java.security.SecureRandom

case class UserStatistics(
    user: User,
    socialUsers: Seq[SocialUserInfo],
    bookmarksCount: Int,
    experiments: Set[ExperimentType],
    kifiInstallations: Seq[KifiInstallation])

class AdminUserController @Inject() (
    actionAuthenticator: ActionAuthenticator,
    db: Database,
    userRepo: UserRepo,
    socialUserInfoRepo: SocialUserInfoRepo,
    followRepo: FollowRepo,
    normalizedURIRepo: NormalizedURIRepo,
    mailRepo: ElectronicMailRepo,
    commentRecipientRepo: CommentRecipientRepo,
    socialUserRawInfoStore: SocialUserRawInfoStore,
    bookmarkRepo: BookmarkRepo,
    socialConnectionRepo: SocialConnectionRepo,
    userConnectionRepo: UserConnectionRepo,
    kifiInstallationRepo: KifiInstallationRepo,
    emailRepo: EmailAddressRepo,
    userExperimentRepo: UserExperimentRepo,
    socialGraphPlugin: SocialGraphPlugin,
    searchClient: SearchServiceClient,
    userValueRepo: UserValueRepo,
    collectionRepo: CollectionRepo,
    keepToCollectionRepo: KeepToCollectionRepo,
    emailAddressRepo: EmailAddressRepo,
    invitationRepo: InvitationRepo,
    userSessionRepo: UserSessionRepo,
    clock: Clock,
    eliza: ElizaServiceClient,
    abookClient: ABookServiceClient,
    serviceDiscovery: ServiceDiscovery) extends AdminController(actionAuthenticator) {

  implicit val dbMasterSlave = Database.Slave

  def merge = AdminHtmlAction { implicit request =>
    // This doesn't do a complete merge. It's designed for cases where someone accidentally creates a new user when
    // logging in and wants to associate the newly-created user's social users with an existing user
    val form = request.request.body.asFormUrlEncoded.get
    val (fromUserId, toUserId) = (Id[User](form("from").head.toLong), Id[User](form("to").head.toLong))

    db.readWrite { implicit s =>
      val fromUser = userRepo.get(fromUserId)
      val toUser = userRepo.get(toUserId)
      for (email <- emailAddressRepo.getByUser(fromUserId)) {
        emailRepo.save(email.copy(userId = toUserId))
      }
      val socialUsers = socialUserInfoRepo.getByUser(fromUserId)
      for (su <- socialUsers; invitation <- invitationRepo.getByRecipient(su.id.get)) {
        invitationRepo.save(invitation.withState(InvitationStates.INACTIVE))
      }
      for (su <- socialUsers) {
        socialUserInfoRepo.save(su.withUser(toUser))
      }
      userRepo.save(toUser.withState(UserStates.ACTIVE))
      userRepo.save(fromUser.withState(UserStates.INACTIVE))

      userConnectionRepo.deactivateAllConnections(fromUserId)

      userSessionRepo.invalidateByUser(fromUserId)
    }

    for (su <- db.readOnly { implicit s => socialUserInfoRepo.getByUser(toUserId) }) {
      socialGraphPlugin.asyncFetch(su)
    }

    Redirect(routes.AdminUserController.userView(toUserId))
  }

  def moreUserInfoView(userId: Id[User]) = AdminHtmlAction { implicit request =>
    val abookInfoF = abookClient.getABookInfos(userId)
    val contactsF = abookClient.getContacts(userId, 40000000)
    val econtactsF = abookClient.getEContacts(userId, 40000000)
    val (user, socialUserInfos, sentElectronicMails) = db.readOnly { implicit s =>
      val user = userRepo.get(userId)
      val socialUserInfos = socialUserInfoRepo.getByUser(user.id.get)
      val sentElectronicMails = mailRepo.forSender(userId)
      (user, socialUserInfos, sentElectronicMails)
    }
    val rawInfos = socialUserInfos map {info =>
      socialUserRawInfoStore.get(info.id.get)
    }
    val abookInfos:Seq[ABookInfo] = Await.result(abookInfoF, 5 seconds)
    val contacts:Seq[Contact] = Await.result(contactsF, 10 seconds)
    val econtacts:Seq[EContact] = Await.result(econtactsF, 10 seconds)
    Ok(html.admin.moreUserInfo(user, rawInfos.flatten, socialUserInfos, sentElectronicMails, abookInfos, contacts, econtacts))
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
    val abookInfoF = abookClient.getABookInfos(userId)
    val contactsF = abookClient.getContacts(userId, 500)
    val econtactsF = abookClient.getEContacts(userId, 500)

    val (user, bookmarks, socialUsers, socialConnections, fortyTwoConnections, kifiInstallations, allowedInvites, emails) = db.readOnly {implicit s =>
      val user = userRepo.get(userId)
      val bookmarks = bookmarkRepo.getByUser(userId)
      val uris = bookmarks map (_.uriId) map normalizedURIRepo.get
      val socialUsers = socialUserInfoRepo.getByUser(userId)
      val socialConnections = socialConnectionRepo.getUserConnections(userId).sortWith((a,b) => a.fullName < b.fullName)
      val fortyTwoConnections = userConnectionRepo.getConnectedUsers(userId).map { userId =>
        userRepo.get(userId)
      }.toSeq.sortBy(u => s"${u.firstName} ${u.lastName}")
      val kifiInstallations = kifiInstallationRepo.all(userId).sortWith((a,b) => a.updatedAt.isBefore(b.updatedAt))
      val allowedInvites = userValueRepo.getValue(user.id.get, "availableInvites").getOrElse("6").toInt
      val emails = emailRepo.getByUser(user.id.get)
      (user, (bookmarks, uris).zipped.toList.seq, socialUsers, socialConnections, fortyTwoConnections, kifiInstallations, allowedInvites, emails)
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

    val abookInfos:Seq[ABookInfo] = Await.result(abookInfoF, 5 seconds)
    val contacts:Seq[Contact] = Await.result(contactsF, 5 seconds)
    val econtacts:Seq[EContact] = Await.result(econtactsF, 5 seconds)
    val abookServiceOpt = serviceDiscovery.serviceCluster(ServiceType.ABOOK).nextService()
    val abookEP = for (s <- abookServiceOpt) yield s"http://${s.instanceInfo.publicIp.ip}:9000/internal/abook/"
    val state = new BigInteger(130, new SecureRandom()).toString(32)

    Ok(html.admin.user(user, bookmarks.size, experiments, filteredBookmarks, socialUsers, socialConnections,
      fortyTwoConnections, kifiInstallations, bookmarkSearch, allowedInvites, emails, abookInfos, contacts, econtacts, abookEP,
      collections, collectionFilter, state)).withSession(session + ("stateToken" -> state ))
  }

  def allUsersView = usersView(0)

  private def userStatistics(user: User)(implicit s: RSession): UserStatistics = {
    val kifiInstallations = kifiInstallationRepo.all(user.id.get).sortWith((a,b) => b.updatedAt.isBefore(a.updatedAt)).take(3)
    UserStatistics(user,
      socialUserInfoRepo.getByUser(user.id.get),
      bookmarkRepo.getCountByUser(user.id.get),
      userExperimentRepo.getUserExperiments(user.id.get),
      kifiInstallations)
  }

  def usersView(page: Int = 0) = AdminHtmlAction { implicit request =>
    val PAGE_SIZE = 50

    val users = db.readOnly { implicit s =>
      userRepo.pageExcluding(UserStates.PENDING, UserStates.INACTIVE, UserStates.BLOCKED)(page, PAGE_SIZE) map userStatistics
    }
    val userCount = db.readOnly { implicit s => userRepo.countExcluding(UserStates.PENDING, UserStates.INACTIVE, UserStates.BLOCKED) }
    Ok(html.admin.users(users, page, userCount, Math.ceil(userCount.toFloat / PAGE_SIZE.toFloat).toInt, None))
  }

  def searchUsers() = AdminHtmlAction { implicit request =>
    val form = request.request.body.asFormUrlEncoded.map{ req => req.map(r => (r._1 -> r._2.head)) }
    val searchTerm = form.flatMap{ _.get("searchTerm") }
    searchTerm match {
      case None => Redirect(routes.AdminUserController.usersView(0))
      case Some(queryText) =>
        val userIds = Await.result(searchClient.searchUsers(queryText, 100, ""), 15 seconds).hits.map{_.id}
        val users = db.readOnly { implicit s =>
          userIds map userRepo.get map userStatistics
        }
        Ok(html.admin.users(users, 0, users.size, 1, searchTerm))
    }
  }

  def searchBasicUsers(queryText: String, maxHits: Int = 10) = AdminHtmlAction { implicit request =>
    val users = Await.result(searchClient.searchUsers(queryText, maxHits, ""), 15 seconds).hits
    Ok(JsArray(users.map{x => Json.toJson(x)}))
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
    val expType = ExperimentType.get(experiment)
    db.readWrite { implicit session =>
      (userExperimentRepo.get(userId, expType, excludeState = None) match {
        case Some(ue) if ue.isActive => None
        case Some(ue) => Some(userExperimentRepo.save(ue.withState(UserExperimentStates.ACTIVE)))
        case None => Some(userExperimentRepo.save(UserExperiment(userId = userId, experimentType = expType)))
      }) foreach { _ =>
        eliza.sendToUser(userId, Json.arr("experiments", userExperimentRepo.getUserExperiments(userId).map(_.value)))
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
      userExperimentRepo.get(userId, ExperimentType.get(experiment)).foreach { ue =>
        userExperimentRepo.save(ue.withState(UserExperimentStates.INACTIVE))
        eliza.sendToUser(userId, Json.arr("experiments", userExperimentRepo.getUserExperiments(userId).map(_.value)))
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

  def notification() = AdminHtmlAction { implicit request =>
    Ok(html.admin.notification(request.user.id.get.id))
  }

  def sendNotificationToAllUsers() = AdminHtmlAction { implicit request =>
    implicit val playRequest = request.request
    val notifyForm = Form(tuple(
      "title" -> text,
      "bodyHtml" -> text,
      "linkText" -> text,
      "url" -> optional(text),
      "image" -> text,
      "sticky" -> optional(text),
      "users" -> optional(text)
    ))

    val (title, bodyHtml, linkText, url, image, sticky, whichUsers) = notifyForm.bindFromRequest.get

    val usersOpt : Option[Seq[Id[User]]] = whichUsers.flatMap(s => if(s == "") None else Some(s) ).map(_.split("[\\s,;]").filter(_ != "").map(u => Id[User](u.toLong)).toSeq)
    val isSticky : Boolean = sticky.map(_ => true).getOrElse(false)

    log.info("Sending global notification via Eliza!")
    usersOpt.map {
      users =>
        eliza.sendGlobalNotification(users.toSet, title, bodyHtml, linkText, url.getOrElse(""), image, isSticky)
    } getOrElse {
      val users = db.readOnly {
        implicit session => userRepo.getAllIds()
      } //Note: Need to revisit when we have >50k users.
      eliza.sendGlobalNotification(users, title, bodyHtml, linkText, url.getOrElse(""), image, isSticky)
    }


    Redirect(routes.AdminUserController.notification())
  }

  def initUserSeq() = AdminHtmlAction { implicit request =>
    db.readWrite{ implicit s =>
      userRepo.all.sortBy(_.id.get.id).foreach{ u => userRepo.save(u) }
    }
    Ok("OK. Assigning user sequence numbers")
  }
}
