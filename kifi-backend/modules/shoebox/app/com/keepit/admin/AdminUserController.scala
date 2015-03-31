package com.keepit.controllers.admin

import com.keepit.commanders.emails.ActivityFeedEmailSender
import com.keepit.curator.CuratorServiceClient
import com.keepit.shoebox.cron.{ ActivityPusher, ActivityPushSchedualer }
import scala.concurrent.{ Await, Future, Promise }
import scala.concurrent.duration.{ Duration, DurationInt }
import scala.util.{ Try }

import com.google.inject.Inject
import com.keepit.abook.ABookServiceClient
import com.keepit.commanders.{ AuthCommander, UserCommander }
import com.keepit.common.akka.SafeFuture
import com.keepit.common.controller.{ AdminUserActions, UserActionsHelper, UserRequest }
import com.keepit.common.db._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.db.slick._
import com.keepit.common.mail._
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.store.S3ImageStore
import com.keepit.common.time._
import com.keepit.eliza.ElizaServiceClient
import com.keepit.eliza.model.UserThreadStats
import com.keepit.heimdal._
import com.keepit.model._
import com.keepit.model.{ UserEmailAddress, KifiInstallation, KeepToCollection, SocialConnection, UserExperiment }
import com.keepit.search.SearchServiceClient
import com.keepit.social.{ BasicUser, SocialId, SocialNetworks, SocialGraphPlugin, SocialUserRawInfoStore }

import play.api.data._
import play.api.data.Forms._
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.json._
import play.api.mvc.{ Action, AnyContent, Result }

import views.html
import com.keepit.typeahead.{ KifiUserTypeahead, TypeaheadHit, SocialUserTypeahead }
import com.keepit.common.healthcheck.SystemAdminMailSender
import com.keepit.abook.model.RichContact

case class UserStatistics(
  user: User,
  connections: Int,
  invitations: Int,
  invitedBy: Seq[User],
  socialUsers: Seq[SocialUserInfo],
  privateKeeps: Int,
  publicKeeps: Int,
  experiments: Set[ExperimentType],
  kifiInstallations: Seq[KifiInstallation],
  librariesCreated: Int,
  librariesFollowed: Int)

case class InvitationInfo(activeInvites: Seq[Invitation], acceptedInvites: Seq[Invitation])

case class UserStatisticsPage(
    userViewType: UserViewType,
    users: Seq[UserStatistics],
    userThreadStats: Map[Id[User], Future[UserThreadStats]],
    page: Int,
    userCount: Int,
    pageSize: Int,
    newUsers: Option[Int],
    recentUsers: Seq[Id[User]] = Seq.empty,
    invitationInfo: Option[InvitationInfo] = None) {

  def getUserThreadStats(user: User): UserThreadStats = Await.result(userThreadStats(user.id.get), Duration.Inf)
}

sealed trait UserViewType
object UserViewTypes {
  case object AllUsersViewType extends UserViewType
  case object RegisteredUsersViewType extends UserViewType
  case object FakeUsersViewType extends UserViewType
  case class ByExperimentUsersViewType(exp: ExperimentType) extends UserViewType
}
import UserViewTypes._

class AdminUserController @Inject() (
    val userActionsHelper: UserActionsHelper,
    db: Database,
    userRepo: UserRepo,
    socialUserInfoRepo: SocialUserInfoRepo,
    normalizedURIRepo: NormalizedURIRepo,
    mailRepo: ElectronicMailRepo,
    socialUserRawInfoStore: SocialUserRawInfoStore,
    keepRepo: KeepRepo,
    socialConnectionRepo: SocialConnectionRepo,
    searchFriendRepo: SearchFriendRepo,
    userConnectionRepo: UserConnectionRepo,
    kifiInstallationRepo: KifiInstallationRepo,
    emailRepo: UserEmailAddressRepo,
    userExperimentRepo: UserExperimentRepo,
    socialGraphPlugin: SocialGraphPlugin,
    searchClient: SearchServiceClient,
    userValueRepo: UserValueRepo,
    collectionRepo: CollectionRepo,
    keepToCollectionRepo: KeepToCollectionRepo,
    libraryRepo: LibraryRepo,
    libraryMembershipRepo: LibraryMembershipRepo,
    libraryInviteRepo: LibraryInviteRepo,
    invitationRepo: InvitationRepo,
    userSessionRepo: UserSessionRepo,
    imageStore: S3ImageStore,
    userPictureRepo: UserPictureRepo,
    basicUserRepo: BasicUserRepo,
    userCredRepo: UserCredRepo,
    userCommander: UserCommander,
    socialUserTypeahead: SocialUserTypeahead,
    kifiUserTypeahead: KifiUserTypeahead,
    systemAdminMailSender: SystemAdminMailSender,
    eliza: ElizaServiceClient,
    abookClient: ABookServiceClient,
    heimdal: HeimdalServiceClient,
    curator: CuratorServiceClient,
    activityEmailSender: ActivityFeedEmailSender,
    activityPushSchedualer: ActivityPushSchedualer,
    activityPusher: ActivityPusher,
    authCommander: AuthCommander) extends AdminUserActions {

  def createPushActivityEntities = AdminUserPage { implicit request =>
    activityPushSchedualer.createPushActivityEntities()
    Ok("started!")
  }

  def pushLibraryActivity = AdminUserPage { implicit request =>
    activityPusher.forcePushLibraryActivityForUser(request.userId)
    Ok("done")
  }

  def merge = AdminUserPage { implicit request =>
    // This doesn't do a complete merge. It's designed for cases where someone accidentally creates a new user when
    // logging in and wants to associate the newly-created user's social users with an existing user
    val form = request.request.body.asFormUrlEncoded.get
    val (fromUserId, toUserId) = (Id[User](form("from").head.toLong), Id[User](form("to").head.toLong))

    db.readWrite { implicit s =>
      val fromUser = userRepo.get(fromUserId)
      val toUser = userRepo.get(toUserId)
      for (email <- emailRepo.getAllByUser(fromUserId)) {
        emailRepo.save(email.copy(userId = toUserId))
      }
      val socialUsers = socialUserInfoRepo.getByUser(fromUserId)
      for (su <- socialUsers; invitation <- invitationRepo.getByRecipientSocialUserId(su.id.get)) {
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

    for (su <- db.readOnlyReplica { implicit s => socialUserInfoRepo.getByUser(toUserId) }) {
      socialGraphPlugin.asyncFetch(su)
    }

    Redirect(routes.AdminUserController.userView(toUserId))
  }

  def moreUserInfoView(userId: Id[User], showPrivates: Boolean = false) = AdminUserPage.async { implicit request =>
    val abookInfoF = abookClient.getABookInfos(userId)
    val contactsF = if (showPrivates) abookClient.getContactsByUser(userId) else Future.successful(Seq.empty[RichContact])
    val (user, socialUserInfos, socialConnections) = db.readOnlyMaster { implicit s =>
      val user = userRepo.get(userId)
      val socialConnections = socialConnectionRepo.getSocialConnectionInfosByUser(userId).valuesIterator.flatten.toSeq.sortWith((a, b) => a.fullName < b.fullName)
      val socialUserInfos = socialUserInfoRepo.getSocialUserBasicInfosByUser(user.id.get)
      (user, socialUserInfos, socialConnections)
    }
    val rawInfos = socialUserInfos map { info =>
      socialUserRawInfoStore.syncGet(info.id)
    }
    for {
      abookInfos <- abookInfoF
      contacts <- contactsF
    } yield Ok(html.admin.moreUserInfo(user, rawInfos.flatten, socialUserInfos, socialConnections, abookInfos, contacts))
  }

  def updateCollectionsForBookmark(id: Id[Keep]) = AdminUserPage { implicit request =>
    request.request.body.asFormUrlEncoded.map { _.map(r => (r._1 -> r._2.head)) }.map { map =>
      val collectionNames = map.get("collections").getOrElse("").split(",").map(_.trim).filterNot(_.isEmpty).map(Hashtag.apply)
      val collections = db.readWrite { implicit s =>
        val bookmark = keepRepo.get(id)
        val userId = bookmark.userId
        val existing = keepToCollectionRepo.getByKeep(id, excludeState = None).map(k => k.collectionId -> k).toMap
        val colls = collectionNames.map { name =>
          val collection = collectionRepo.getByUserAndName(userId, name, excludeState = None) match {
            case Some(coll) if coll.isActive => coll
            case Some(coll) => collectionRepo.save(coll.copy(state = CollectionStates.ACTIVE))
            case None => collectionRepo.save(Collection(userId = userId, name = name))
          }
          existing.get(collection.id.get) match {
            case Some(ktc) if ktc.isActive => ktc
            case Some(ktc) => keepToCollectionRepo.save(ktc.copy(state = KeepToCollectionStates.ACTIVE))
            case None => keepToCollectionRepo.save(KeepToCollection(keepId = id, collectionId = collection.id.get))
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

  def userView(userId: Id[User], showPrivates: Boolean = false) = AdminUserPage.async { implicit request =>
    doUserViewById(userId, showPrivates)
  }

  def userKeepsView(userId: Id[User], showPrivates: Boolean = false) = AdminUserPage { implicit request =>
    doUserKeepsView(userId, showPrivates)
  }

  def userViewByEitherId(userIdStr: String, showPrivates: Boolean = false) = AdminUserPage.async { implicit request =>
    Try(userIdStr.toLong).toOption map { id =>
      doUserViewById(Id[User](id), showPrivates)
    } orElse {
      ExternalId.asOpt[User](userIdStr) flatMap { userExtId =>
        db.readOnlyReplica { implicit session =>
          userRepo.getOpt(userExtId)
        }
      } map { user =>
        doUserView(user, showPrivates)
      }
    } getOrElse Promise.successful(NotFound).future
  }

  private def doUserViewById(userId: Id[User], showPrivates: Boolean)(implicit request: UserRequest[AnyContent]): Future[Result] = {
    db.readOnlyReplica { implicit session =>
      Try(userRepo.get(userId))
    } map { user =>
      doUserView(user, showPrivates)
    } getOrElse Promise.successful(NotFound).future
  }

  private def doUserView(user: User, showPrivateContacts: Boolean)(implicit request: UserRequest[AnyContent]): Future[Result] = {
    var userId = user.id.get
    val abookInfoF = abookClient.getABookInfos(userId)
    val econtactCountF = abookClient.getEContactCount(userId)
    val contactsF = if (showPrivateContacts) abookClient.getContactsByUser(userId, pageSize = Some(500)) else Future.successful(Seq.empty[RichContact])

    val (bookmarkCount, socialUsers, fortyTwoConnections, kifiInstallations, allowedInvites, emails, invitedByUsers) = db.readOnlyReplica { implicit s =>
      val bookmarkCount = keepRepo.getCountByUser(userId)
      val socialUsers = socialUserInfoRepo.getSocialUserBasicInfosByUser(userId)
      val fortyTwoConnections = userConnectionRepo.getConnectedUsers(userId).map { userId =>
        userRepo.get(userId)
      }.toSeq.sortBy(u => s"${u.firstName} ${u.lastName}")
      val kifiInstallations = kifiInstallationRepo.all(userId).sortWith((a, b) => a.updatedAt.isBefore(b.updatedAt))
      val allowedInvites = userValueRepo.getValue(userId, UserValues.availableInvites)
      val emails = emailRepo.getAllByUser(userId)
      val invitedByUsers = invitedBy(socialUsers.map(_.id), emails)
      (bookmarkCount, socialUsers, fortyTwoConnections, kifiInstallations, allowedInvites, emails, invitedByUsers)
    }

    val experiments = db.readOnlyReplica { implicit s => userExperimentRepo.getUserExperiments(user.id.get) }

    for {
      abookInfos <- abookInfoF
      econtactCount <- econtactCountF
      contacts <- contactsF
    } yield {
      Ok(html.admin.user(user, bookmarkCount, experiments, socialUsers,
        fortyTwoConnections, kifiInstallations, allowedInvites, emails, abookInfos, econtactCount,
        contacts, invitedByUsers))
    }
  }

  private def doUserKeepsView(userId: Id[User], showPrivates: Boolean)(implicit request: UserRequest[AnyContent]): Result = {
    if (showPrivates) {
      log.warn(s"${request.user.firstName} ${request.user.firstName} (${request.userId}) is viewing user $userId's private keeps and contacts")
    }

    val (user, bookmarks) = db.readOnlyReplica { implicit s =>
      val user = userRepo.get(userId)
      val bookmarks = keepRepo.getByUser(userId, Set(KeepStates.INACTIVE)).filter(b => showPrivates || !b.isPrivate)
      val uris = bookmarks map (_.uriId) map normalizedURIRepo.get
      (user, (bookmarks, uris).zipped.toList.seq)
    }

    val form = request.request.body.asFormUrlEncoded.map { req => req.map(r => (r._1 -> r._2.head)) }

    val bookmarkSearch = form.flatMap { _.get("bookmarkSearch") }
    val collectionFilter = form.flatMap(_.get("collectionFilter")).collect {
      case cid if cid.toLong > 0 => Id[Collection](cid.toLong)
    }
    val bookmarkFilter = collectionFilter.map { collId =>
      db.readOnlyReplica { implicit s => keepToCollectionRepo.getKeepsForTag(collId) }
    }
    val filteredBookmarks = db.readOnlyReplica { implicit s =>
      val query = bookmarkSearch.getOrElse("").toLowerCase()
      (if (query.trim.length == 0) {
        bookmarks
      } else {
        bookmarks.filter { case (b, u) => b.title.exists { t => t.toLowerCase().indexOf(query) >= 0 } }
      }) collect {
        case (mark, uri) if bookmarkFilter.isEmpty || bookmarkFilter.get.contains(mark.id.get) =>
          val colls = keepToCollectionRepo.getCollectionsForKeep(mark.id.get).map(collectionRepo.get).map(_.name)
          (mark, uri, colls)
      }
    }
    val collections = db.readOnlyReplica { implicit s => collectionRepo.getUnfortunatelyIncompleteTagsByUser(userId) }

    Ok(html.admin.userKeeps(user, bookmarks.size, filteredBookmarks, bookmarkSearch, collections, collectionFilter))
  }

  def allUsersView = usersView(0)
  def allRegisteredUsersView = registeredUsersView(0)
  def allFakeUsersView = fakeUsersView(0)

  private def invitedBy(socialUserIds: Seq[Id[SocialUserInfo]], emails: Seq[UserEmailAddress])(implicit s: RSession): Seq[User] = {
    val invites = invitationRepo.getByRecipientSocialUserIdsAndEmailAddresses(socialUserIds.toSet, emails.map(_.address).toSet)
    val inviters = invites.map(_.senderUserId).flatten
    userRepo.getAllUsers(inviters).values.toSeq
  }

  private def userStatistics(user: User, socialUserInfos: Map[Id[User], Seq[SocialUserInfo]])(implicit s: RSession): UserStatistics = {
    val kifiInstallations = kifiInstallationRepo.all(user.id.get).sortWith((a, b) => b.updatedAt.isBefore(a.updatedAt)).take(3)
    val (privateKeeps, publicKeeps) = keepRepo.getPrivatePublicCountByUser(user.id.get)
    val emails = emailRepo.getAllByUser(user.id.get)
    val librariesCountsByAccess = libraryMembershipRepo.countsWithUserIdAndAccesses(user.id.get, Set(LibraryAccess.OWNER, LibraryAccess.READ_ONLY))
    val librariesCreated = librariesCountsByAccess(LibraryAccess.OWNER) - 2 //ignoring main and secret
    val librariesFollowed = librariesCountsByAccess(LibraryAccess.READ_ONLY)

    UserStatistics(user,
      userConnectionRepo.getConnectionCount(user.id.get),
      invitationRepo.countByUser(user.id.get),
      invitedBy(socialUserInfos.getOrElse(user.id.get, Seq()).map(_.id.get), emails),
      socialUserInfos.getOrElse(user.id.get, Seq()),
      privateKeeps,
      publicKeeps,
      userExperimentRepo.getUserExperiments(user.id.get),
      kifiInstallations,
      librariesCreated,
      librariesFollowed)
  }

  def userStatisticsPage(page: Int = 0, userViewType: UserViewType): Future[UserStatisticsPage] = {
    val PAGE_SIZE: Int = 30

    val usersF = Future {
      db.readOnlyReplica { implicit s =>
        userViewType match {
          case AllUsersViewType => (userRepo.pageIncluding(UserStates.ACTIVE)(page, PAGE_SIZE),
            userRepo.countIncluding(UserStates.ACTIVE))
          case RegisteredUsersViewType => (userRepo.pageIncludingWithoutExp(UserStates.ACTIVE)(ExperimentType.FAKE, ExperimentType.AUTO_GEN)(page, PAGE_SIZE),
            userRepo.countIncludingWithoutExp(UserStates.ACTIVE)(ExperimentType.FAKE, ExperimentType.AUTO_GEN))
          case FakeUsersViewType => (userRepo.pageIncludingWithExp(UserStates.ACTIVE)(ExperimentType.FAKE, ExperimentType.AUTO_GEN)(page, PAGE_SIZE),
            userRepo.countIncludingWithExp(UserStates.ACTIVE)(ExperimentType.FAKE, ExperimentType.AUTO_GEN))
          case ByExperimentUsersViewType(exp) => (userRepo.pageIncludingWithExp(UserStates.ACTIVE)(exp)(page, PAGE_SIZE),
            userRepo.countIncludingWithExp(UserStates.ACTIVE)(exp))
        }
      }
    }

    val userStatsF = usersF.map {
      case (users, userCount) =>
        db.readOnlyReplica { implicit s =>
          val socialUserInfos = socialUserInfoRepo.getByUsers(users.map(_.id.get)).groupBy(_.userId.get)
          (users.map(u => userStatistics(u, socialUserInfos)), userCount)
        }
    }

    val userThreadStatsF = usersF.map {
      case (users, _) => (users.map(u => (u.id.get -> eliza.getUserThreadStats(u.id.get)))).seq.toMap
    }

    val (newUsers, recentUsers, inviteInfo) = userViewType match {
      case RegisteredUsersViewType =>
        db.readOnlyReplica { implicit s =>
          val invites = invitationRepo.getRecentInvites()
          val (accepted, sent) = invites.partition(_.state == InvitationStates.ACCEPTED)
          val recentUsers = userRepo.getRecentActiveUsers()
          (Some(userRepo.countNewUsers), recentUsers, Some(InvitationInfo(sent, accepted)))
        }
      case _ =>
        (None, Seq.empty, None)
    }

    (userStatsF zip userThreadStatsF).map {
      case ((users, userCount), userThreadStats) =>
        UserStatisticsPage(userViewType, users, userThreadStats, page, userCount, PAGE_SIZE, newUsers, recentUsers, inviteInfo)
    }
  }

  def usersView(page: Int = 0) = AdminUserPage.async { implicit request =>
    userStatisticsPage(page, AllUsersViewType).map { p => Ok(html.admin.users(p, None)) }
  }

  def registeredUsersView(page: Int = 0) = AdminUserPage.async { implicit request =>
    userStatisticsPage(page, RegisteredUsersViewType).map { p => Ok(html.admin.users(p, None)) }
  }

  def fakeUsersView(page: Int = 0) = AdminUserPage.async { implicit request =>
    userStatisticsPage(page, FakeUsersViewType).map { p => Ok(html.admin.users(p, None)) }
  }

  def byExperimentUsersView(page: Int, exp: String) = AdminUserPage.async { implicit request =>
    userStatisticsPage(page, ByExperimentUsersViewType(ExperimentType(exp))).map { p => Ok(html.admin.users(p, None)) }
  }

  def searchUsers() = AdminUserPage { implicit request =>
    val form = request.request.body.asFormUrlEncoded.map { req => req.map(r => (r._1 -> r._2.head)) }
    val searchTerm = form.flatMap { _.get("searchTerm") }
    searchTerm match {
      case None => Redirect(routes.AdminUserController.usersView(0))
      case Some(queryText) =>
        val userIds = Await.result(searchClient.searchUsers(userId = None, query = queryText, maxHits = 100), 15 seconds).hits.map { _.id }
        val users = db.readOnlyReplica { implicit s =>
          val socialUserInfos = socialUserInfoRepo.getByUsers(userIds).groupBy(_.userId.get)
          userIds.map(userRepo.get).map(u => userStatistics(u, socialUserInfos))
        }
        val userThreadStats = (users.par.map { u =>
          val userId = u.user.id.get
          (userId -> eliza.getUserThreadStats(u.user.id.get))
        }).seq.toMap
        Ok(html.admin.users(UserStatisticsPage(AllUsersViewType, users, userThreadStats, 0, users.size, users.size, None), searchTerm))
    }
  }

  def updateUser(userId: Id[User]) = AdminUserPage { implicit request =>
    val form = request.request.body.asFormUrlEncoded match {
      case Some(req) => req.map(r => (r._1 -> r._2.head))
      case None => throw new Exception("whoops")
    }

    // We want to throw an exception (.get) if `emails' was not passed in. As we expand this, we should add Play! form validation
    val emailList = form.get("emails").get.split(",").map(_.toLowerCase().trim()).toList.distinct.map(EmailAddress.validate(_).toOption).flatten

    db.readWrite { implicit session =>
      val oldEmails = emailRepo.getAllByUser(userId).toSet
      val newEmails = (emailList map { address =>
        val email = emailRepo.getByAddressOpt(address)
        email match {
          case Some(addr) => addr // We're good! It already exists
          case None => // Create a new one
            log.info("Adding email address %s to userId %s".format(address, userId.toString))
            emailRepo.save(UserEmailAddress(address = address, userId = userId))
        }
      }).toSet

      // Set state of removed email addresses to INACTIVE
      (oldEmails -- newEmails) map { removedEmail =>
        log.info("Removing email address %s from userId %s".format(removedEmail.address, userId.toString))
        emailRepo.save(removedEmail.withState(UserEmailAddressStates.INACTIVE))
      }
    }

    Redirect(com.keepit.controllers.admin.routes.AdminUserController.userView(userId))
  }

  def setInvitesCount(userId: Id[User]) = AdminUserPage { implicit request =>
    val count = request.request.body.asFormUrlEncoded.get("allowedInvites").headOption.getOrElse("1000")
    db.readWrite { implicit session =>
      userValueRepo.setValue(userId, UserValues.availableInvites.name, count)
    }
    Redirect(routes.AdminUserController.userView(userId))
  }

  //todo: this code may become hard to maintain, should be unified with the production path
  def connectUsers(user1: Id[User]) = AdminUserPage { implicit request =>
    val user2 = Id[User](request.body.asFormUrlEncoded.get.apply("user2").head.toLong)
    db.readWrite { implicit session =>
      val socialUser1 = socialUserInfoRepo.getByUser(user1).find(_.networkType == SocialNetworks.FORTYTWO)
      val socialUser2 = socialUserInfoRepo.getByUser(user2).find(_.networkType == SocialNetworks.FORTYTWO)
      for {
        su1 <- socialUser1
        su2 <- socialUser2
      } yield {
        socialConnectionRepo.getConnectionOpt(su1.id.get, su2.id.get) match {
          case Some(sc) =>
            socialConnectionRepo.save(sc.withState(SocialConnectionStates.ACTIVE))
          case None =>
            socialConnectionRepo.save(SocialConnection(socialUser1 = su1.id.get, socialUser2 = su2.id.get, state = SocialConnectionStates.ACTIVE))
        }
      }
      userConnectionRepo.addConnections(user1, Set(user2), requested = true)
      eliza.sendToUser(user1, Json.arr("new_friends", Set(basicUserRepo.load(user2))))
      eliza.sendToUser(user2, Json.arr("new_friends", Set(basicUserRepo.load(user1))))
    }
    Seq(user1, user2) foreach { userId =>
      socialUserTypeahead.refresh(userId)
      kifiUserTypeahead.refresh(userId)
    }
    Redirect(routes.AdminUserController.userView(user1))
  }

  def addExperiment(userId: Id[User], experiment: String) = AdminUserAction { request =>
    val expType = ExperimentType.get(experiment)
    db.readWrite { implicit session =>
      (userExperimentRepo.get(userId, expType, excludeState = None) match {
        case Some(ue) if ue.isActive => None
        case Some(ue) => Some(userExperimentRepo.save(ue.withState(UserExperimentStates.ACTIVE)))
        case None => Some(userExperimentRepo.save(UserExperiment(userId = userId, experimentType = expType)))
      }) foreach { _ =>
        val experiments = userExperimentRepo.getUserExperiments(userId)
        eliza.sendToUser(userId, Json.arr("experiments", experiments.map(_.value)))
        heimdal.setUserProperties(userId, "experiments" -> ContextList(experiments.map(exp => ContextStringData(exp.value)).toSeq))
      }
      userRepo.save(userRepo.getNoCache(userId)) // update user index sequence number
    }
    Ok(Json.obj(experiment -> true))
  }

  def changeUsersName(userId: Id[User]) = AdminUserPage { request =>
    db.readWrite { implicit session =>
      val user = userRepo.getNoCache(userId)
      val first = request.body.asFormUrlEncoded.map(_.apply("first").headOption.map(_.trim)).flatten.getOrElse(user.firstName)
      val last = request.body.asFormUrlEncoded.map(_.apply("last").headOption.map(_.trim)).flatten.getOrElse(user.lastName)
      userRepo.save(user.copy(firstName = first, lastName = last))
      Ok
    }
  }

  def setUserPicture(userId: Id[User], pictureId: Id[UserPicture]) = AdminUserPage { request =>
    db.readWrite { implicit session =>
      val user = userRepo.get(userId)
      userPictureRepo.getByUser(userId).find(_.id.get == pictureId) map { pic =>
        if (pic.state != UserPictureStates.ACTIVE) {
          userPictureRepo.save(pic.withState(UserPictureStates.ACTIVE))
        }
        userRepo.save(user.copy(pictureName = Some(pic.name), userPictureId = pic.id))
      }
    } map { user =>
      eliza.sendToUser(userId, Json.arr("new_pic", BasicUser.fromUser(user).pictureName))
      Ok
    } getOrElse {
      BadRequest
    }
  }

  def userValue(userId: Id[User]) = AdminUserAction { implicit request =>
    val req = request.body.asJson.map { json =>
      ((json \ "name").asOpt[UserValueName], (json \ "value").asOpt[String], (json \ "clear").asOpt[Boolean]) match {
        case (Some(name), Some(value), None) =>
          Some(db.readWrite { implicit session => // set it
            userValueRepo.setValue(userId, name, value)
          })
        case (Some(name), _, Some(c)) => // clear it
          Some(db.readWrite { implicit session =>
            userValueRepo.clearValue(userId, name).toString
          })
        case (Some(name), _, _) => // get it
          db.readOnlyMaster { implicit session =>
            userValueRepo.getValueStringOpt(userId, name)
          }
        case _ =>
          None.asInstanceOf[Option[String]]
      }
    }.flatten

    req.map { result =>
      Ok(Json.obj("success" -> result))
    }.getOrElse {
      BadRequest(Json.obj("didyoudoit" -> "noididnt"))
    }
  }

  def changeState(userId: Id[User], state: String) = AdminUserAction { request =>
    val userState = state match {
      case UserStates.ACTIVE.value => UserStates.ACTIVE
      case UserStates.INACTIVE.value => UserStates.INACTIVE
      case UserStates.BLOCKED.value => UserStates.BLOCKED
      case UserStates.PENDING.value => UserStates.PENDING
    }

    db.readWrite(implicit s => userRepo.save(userRepo.get(userId).withState(userState)))
    Ok
  }

  def removeExperiment(userId: Id[User], experiment: String) = AdminUserAction { request =>
    db.readWrite { implicit session =>
      val ue: Option[UserExperiment] = userExperimentRepo.get(userId, ExperimentType(experiment))
      ue foreach { ue =>
        userExperimentRepo.save(ue.withState(UserExperimentStates.INACTIVE))
        val experiments = userExperimentRepo.getUserExperiments(userId)
        eliza.sendToUser(userId, Json.arr("experiments", experiments.map(_.value)))
        heimdal.setUserProperties(userId, "experiments" -> ContextList(experiments.map(exp => ContextStringData(exp.value)).toSeq))
        userRepo.save(userRepo.getNoCache(userId)) // update user index sequence number
      }
    }
    Ok(Json.obj(experiment -> false))
  }

  def refreshAllSocialInfo(userId: Id[User]) = AdminUserPage { implicit request =>
    val socialUserInfos = db.readOnlyMaster { implicit s =>
      val user = userRepo.get(userId)
      socialUserInfoRepo.getByUser(user.id.get)
    }
    socialUserInfos.map { info =>
      socialGraphPlugin.asyncFetch(info)
    }
    Redirect(com.keepit.controllers.admin.routes.AdminUserController.userView(userId))
  }

  def updateUserPicture(userId: Id[User]) = AdminUserPage { request =>
    imageStore.forceUpdateSocialPictures(userId)
    Ok
  }

  def notification() = AdminUserPage { implicit request =>
    Ok(html.admin.notification(request.user.id.get.id))
  }

  def sendNotificationToAllUsers() = AdminUserPage { implicit request =>
    implicit val playRequest = request.request
    val notifyForm = Form(tuple(
      "title" -> text,
      "bodyHtml" -> text,
      "linkText" -> text,
      "url" -> optional(text),
      "image" -> text,
      "sticky" -> optional(text),
      "users" -> optional(text),
      "category" -> optional(text)
    ))

    val (title, bodyHtml, linkText, url, image, sticky, whichUsers, categoryOpt) = notifyForm.bindFromRequest.get
    val category = categoryOpt.map(NotificationCategory.apply) getOrElse NotificationCategory.User.ANNOUNCEMENT

    val usersOpt: Option[Seq[Id[User]]] = whichUsers.flatMap(s => if (s == "") None else Some(s)).map(_.split("[\\s,;]").filter(_ != "").map(u => Id[User](u.toLong)).toSeq)
    val isSticky: Boolean = sticky.isDefined

    log.info("Sending global notification via Eliza!")
    usersOpt.map {
      users =>
        eliza.sendGlobalNotification(users.toSet, title, bodyHtml, linkText, url.getOrElse(""), image, isSticky, category)
    } getOrElse {
      val users = db.readOnlyReplica {
        implicit session => userRepo.getAllIds()
      } //Note: Need to revisit when we have >50k users.
      eliza.sendGlobalNotification(users, title, bodyHtml, linkText, url.getOrElse(""), image, isSticky, category)
    }

    Redirect(routes.AdminUserController.notification())
  }

  def bumpUserSeq() = AdminUserPage { implicit request =>
    db.readWrite { implicit s =>
      userRepo.all.sortBy(_.id.get.id).foreach { u => userRepo.save(u) }
    }
    Ok("OK. Bumping up user sequence numbers")
  }

  def resetMixpanelProfile(userId: Id[User]) = AdminUserPage.async { implicit request =>
    SafeFuture {
      val user = db.readOnlyReplica { implicit session => userRepo.get(userId) }
      doResetMixpanelProfile(user)
      Redirect(routes.AdminUserController.userView(userId))
    }
  }

  def deleteAllMixpanelProfiles() = AdminUserPage.async { implicit request =>
    SafeFuture {
      val allUsers = db.readOnlyReplica { implicit s => userRepo.all }
      allUsers.foreach(user => heimdal.deleteUser(user.id.get))
      Ok("All user profiles have been deleted from Mixpanel")
    }
  }

  def resetAllMixpanelProfiles() = AdminUserPage.async { implicit request =>
    SafeFuture {
      val allUsers = db.readOnlyReplica { implicit s => userRepo.all }
      allUsers.foreach(doResetMixpanelProfile)
      Ok("All user profiles have been reset in Mixpanel")
    }
  }

  private def doResetMixpanelProfile(user: User) = {
    val userId = user.id.get
    heimdal.setUserAlias(user.id.get, user.externalId)
    if (user.state == UserStates.INACTIVE)
      heimdal.deleteUser(userId)
    else {
      val properties = new HeimdalContextBuilder
      db.readOnlyMaster { implicit session =>
        properties += ("$first_name", user.firstName)
        properties += ("$last_name", user.lastName)
        properties += ("$created", user.createdAt)
        user.primaryEmail.foreach { primaryEmail => properties += ("$email", primaryEmail.address) }
        properties += ("state", user.state.value)
        properties += ("userId", user.id.get.id)
        properties += ("admin", "https://admin.kifi.com" + com.keepit.controllers.admin.routes.AdminUserController.userView(user.id.get).url)

        val (privateKeeps, publicKeeps) = keepRepo.getPrivatePublicCountByUser(userId)
        val keeps = privateKeeps + publicKeeps
        properties += ("keeps", keeps)
        properties += ("publicKeeps", publicKeeps)
        properties += ("privateKeeps", privateKeeps)
        properties += ("tags", collectionRepo.count(userId))
        properties += ("kifiConnections", userConnectionRepo.getConnectionCount(userId))
        properties += ("socialConnections", socialConnectionRepo.getUserConnectionCount(userId))
        properties += ("experiments", userExperimentRepo.getUserExperiments(userId).map(_.value).toSeq)

        val allInstallations = kifiInstallationRepo.all(userId)
        if (allInstallations.nonEmpty) { properties += ("installedExtension", allInstallations.maxBy(_.updatedAt).version.toString) }
        userValueRepo.getValueStringOpt(userId, Gender.key).foreach { gender => properties += (Gender.key.name, Gender(gender).toString) }
      }
      heimdal.setUserProperties(userId, properties.data.toSeq: _*)
    }
  }

  def bumpUpSeqNumForConnections() = AdminUserPage.async { implicit request =>
    SafeFuture {
      val conns = db.readOnlyReplica { implicit s =>
        userConnectionRepo.all()
      }

      conns.grouped(100).foreach { cs =>
        db.readWrite { implicit s =>
          cs.foreach { c => userConnectionRepo.save(c) }
        }
      }

      val friends = db.readOnlyReplica { implicit s =>
        searchFriendRepo.all()
      }

      friends.grouped(100).foreach { fs =>
        db.readWrite { implicit s =>
          fs.foreach { f => searchFriendRepo.save(f) }
        }
      }
      Ok("bump up seqNum for userConnRepo and searchFriendRepo")
    }
  }

  // ad hoc testing only during dev phase
  private def prefixSocialSearchDirect(userId: Id[User], query: String): Future[Seq[SocialUserBasicInfo]] = {
    implicit val ord = TypeaheadHit.defaultOrdering[SocialUserBasicInfo]
    socialUserTypeahead.topN(userId, query, None).map { hits => hits.map(_.info) }
  }

  def prefixSocialSearch(userId: Id[User], query: String) = AdminUserPage.async { request =>
    prefixSocialSearchDirect(userId, query) map { res =>
      Ok(res.map { info => s"SocialUser: id=${info.id} name=${info.fullName} network=${info.networkType} <br/>" }.mkString(""))
    }
  }

  private def prefixContactSearchDirect(userId: Id[User], query: String): Future[Seq[RichContact]] = {
    abookClient.prefixQuery(userId, query) map { res =>
      log.info(s"[prefixContactSearchDirect($userId)-ABOOK] res=(${res.length});${res.take(10).mkString(",")}")
      res.map(_.info)
    }
  }

  def prefixContactSearch(userId: Id[User], query: String) = AdminUserPage.async { request =>
    prefixContactSearchDirect(userId, query) map { res =>
      if (res.isEmpty)
        Ok(s"No contact match found for $query")
      else
        Ok(res.map { e => s"Contact: email=${e.email} name=${e.name} userId=${e.userId}" }.mkString("<br/>"))
    }
  }

  def prefixSearch(userId: Id[User], query: String) = AdminUserPage.async { request =>
    for {
      contactRes <- prefixContactSearchDirect(userId, query)
      socialRes <- prefixSocialSearchDirect(userId, query)
    } yield {
      Ok((
        socialRes.map { info => s"SocialUser: id=${info.id} name=${info.fullName} network=${info.networkType}" } ++
        contactRes.map { e => s"Contact: email=${e.email} name=${e.name} userId=${e.userId}" }
      ).mkString("<br/>"))
    }
  }

  def fixMissingFortyTwoSocialConnections(readOnly: Boolean = true) = AdminUserPage.async { request =>
    SafeFuture {
      val toBeCreated = db.readWrite { implicit session =>
        userConnectionRepo.all().collect {
          case activeConnection if {
            val user1State = userRepo.get(activeConnection.user1).state
            val user2State = userRepo.get(activeConnection.user2).state
            activeConnection.state == UserConnectionStates.ACTIVE && (user1State == UserStates.ACTIVE || user1State == UserStates.BLOCKED) && (user2State == UserStates.ACTIVE || user2State == UserStates.BLOCKED)
          } =>
            val fortyTwoUser1 = socialUserInfoRepo.getByUser(activeConnection.user1).find(_.networkType == SocialNetworks.FORTYTWO).get.id.get
            val fortyTwoUser2 = socialUserInfoRepo.getByUser(activeConnection.user2).find(_.networkType == SocialNetworks.FORTYTWO).get.id.get
            if (socialConnectionRepo.getConnectionOpt(fortyTwoUser1, fortyTwoUser2).isEmpty) {
              if (!readOnly) { socialConnectionRepo.save(SocialConnection(socialUser1 = fortyTwoUser1, socialUser2 = fortyTwoUser2)) }
              Some((activeConnection.user1, fortyTwoUser1, activeConnection.user2, fortyTwoUser2))
            } else None
        }.flatten
      }

      implicit val socialUserInfoIdFormat = Id.format[SocialUserInfo]
      implicit val userIdFormat = Id.format[User]
      val json = JsArray(toBeCreated.map { case (user1, fortyTwoUser1, user2, fortyTwoUser2) => Json.obj("user1" -> user1, "fortyTwoUser1" -> fortyTwoUser1, "user2" -> user2, "fortyTwoUser2" -> fortyTwoUser2) })
      val title = "FortyTwo Connections to be created"
      val msg = toBeCreated.mkString("\n")
      systemAdminMailSender.sendMail(ElectronicMail(from = SystemEmailAddress.ENG, to = List(SystemEmailAddress.LÉO),
        subject = title, htmlBody = msg, category = NotificationCategory.System.ADMIN))
      Ok(json)
    }
  }

  def deactivate(userId: Id[User]) = AdminUserPage.async { request =>
    SafeFuture {
      // todo(Léo): this procedure is incomplete (e.g. does not deal with ABook or Eliza), and should probably be moved to UserCommander and unified with AutoGen Reaper
      val doIt = request.body.asFormUrlEncoded.get.get("doIt").exists(_.head == "true")
      val json = db.readWrite { implicit session =>
        if (doIt) {

          // Social Graph
          userConnectionRepo.deactivateAllConnections(userId) // User Connections
          socialUserInfoRepo.getByUser(userId).foreach { sui =>
            socialConnectionRepo.deactivateAllConnections(sui.id.get) // Social Connections
            invitationRepo.getByRecipientSocialUserId(sui.id.get).foreach(invitation => invitationRepo.save(invitation.withState(InvitationStates.INACTIVE)))
            socialUserInfoRepo.save(sui.withState(SocialUserInfoStates.INACTIVE).copy(userId = None, credentials = None, socialId = SocialId(ExternalId[Nothing]().id))) // Social User Infos
            socialUserInfoRepo.deleteCache(sui)
          }

          // URI Graph
          keepRepo.getByUser(userId).foreach { bookmark => keepRepo.save(bookmark.withActive(false)) }
          collectionRepo.getUnfortunatelyIncompleteTagsByUser(userId).foreach { collection => collectionRepo.save(collection.copy(state = CollectionStates.INACTIVE)) }

          // Libraries Data
          libraryInviteRepo.getByUser(userId, Set(LibraryInviteStates.INACTIVE)).foreach { case (invite, _) => libraryInviteRepo.save(invite.withState(LibraryInviteStates.INACTIVE)) } // Library Invites
          libraryMembershipRepo.getWithUserId(userId).foreach { membership => libraryMembershipRepo.save(membership.withState(LibraryMembershipStates.INACTIVE)) } // Library Memberships
          libraryRepo.getAllByOwner(userId).foreach { library => libraryRepo.save(library.withState(LibraryStates.INACTIVE)) } // Libraries

          // Personal Info
          userSessionRepo.invalidateByUser(userId) // User Session
          kifiInstallationRepo.all(userId).foreach { installation => kifiInstallationRepo.save(installation.withState(KifiInstallationStates.INACTIVE)) } // Kifi Installations
          userCredRepo.findByUserIdOpt(userId).foreach { userCred => userCredRepo.save(userCred.copy(state = UserCredStates.INACTIVE)) } // User Credentials
          emailRepo.getAllByUser(userId).foreach { email => emailRepo.save(email.withState(UserEmailAddressStates.INACTIVE)) } // Email addresses
          userRepo.save(userRepo.get(userId).withState(UserStates.INACTIVE).copy(primaryEmail = None)) // User
        }

        val user = userRepo.get(userId)
        val emails = emailRepo.getAllByUser(userId)
        val credentials = userCredRepo.findByUserIdOpt(userId)
        val installations = kifiInstallationRepo.all(userId)
        val tags = collectionRepo.getUnfortunatelyIncompleteTagsByUser(userId)
        val keeps = keepRepo.getByUser(userId)
        val socialUsers = socialUserInfoRepo.getByUser(userId)
        val socialConnections = socialConnectionRepo.getSocialConnectionInfosByUser(userId)
        val userConnections = userConnectionRepo.getConnectedUsers(userId)
        implicit val userIdFormat = Id.format[User]
        Json.obj(
          "user" -> user,
          "emails" -> emails.map(_.address),
          "credentials" -> credentials.map(_.credentials),
          "installations" -> JsObject(installations.map(installation => installation.userAgent.name -> JsString(installation.version.toString))),
          "tags" -> tags,
          "keeps" -> keeps,
          "socialUsers" -> socialUsers,
          "socialConnections" -> JsObject(socialConnections.toSeq.map { case (network, connections) => network.name -> Json.toJson(connections) }),
          "userConnections" -> userConnections
        )
      }
      Ok(json)
    }
  }

  def deactivateUserEmailAddress(id: Id[UserEmailAddress]) = AdminUserAction { request =>
    log.info(s"About to deactivate UserEmailAddress $id")
    val inactiveEmail = db.readWrite { implicit session =>
      val userEmail = emailRepo.get(id)
      userRepo.save(userRepo.get(userEmail.userId)) // bump up sequence number for reindexing
      emailRepo.save(userEmail.withState(UserEmailAddressStates.INACTIVE))
    }
    log.info(s"Deactivated UserEmailAddress $inactiveEmail")
    Ok(JsString(inactiveEmail.toString))
  }

  def setUsername(userId: Id[User], username: String, overrideValidityCheck: Boolean = false, overrideProtection: Boolean = false) = AdminUserPage { request =>
    val res = userCommander.setUsername(userId, Username(username), overrideValidityCheck = overrideValidityCheck, overrideProtection = overrideProtection)

    Ok(res.toString)
  }

  def userLibrariesView(ownerId: Id[User], showSecrets: Boolean = false) = AdminUserPage { implicit request =>
    if (showSecrets) {
      log.warn(s"${request.user.firstName} ${request.user.firstName} (${request.userId}) is viewing secret libraries of $ownerId")
    }
    val (owner, accessToLibs) = db.readOnlyReplica { implicit session =>
      val owner = userRepo.get(ownerId)
      val libs = libraryRepo.getByUser(ownerId).filter(pair => showSecrets || !(pair._2.visibility == LibraryVisibility.SECRET))
      val accessToLibs: Seq[(LibraryAccess, Library)] = for (libPair <- libs) yield { (libPair._1.access, libPair._2) }
      (owner, accessToLibs)
    }
    Ok(html.admin.userLibraries(owner, accessToLibs))
  }

  def sendActivityEmailToAll() = AdminUserPage(parse.tolerantJson) { implicit request =>
    // Usage example:
    // $.ajax({url:"/admin/sendActivityEmailToAll", type: 'POST', data: JSON.stringify({overrideToEmail:"YOUR_EMAIL_FOR_TESTING@kifi.com"}), contentType: 'application/json', dataType: 'json'});

    val forceSendToAll = (request.request.body \ "forceToAll").asOpt[Boolean]
    val toEmail = (request.request.body \ "overrideToEmail").asOpt[EmailAddress]
    val toUsers = (request.request.body \ "userIds").asOpt[Seq[Id[User]]]

    if (toUsers.isDefined) {
      activityEmailSender(toUsers.get.toSet, toEmail)
      NoContent
    } else if (toEmail.isDefined) {
      activityEmailSender(toEmail)
      NoContent
    } else if (forceSendToAll.exists(true ==)) {
      activityEmailSender(None)
      NoContent
    } else {
      UnprocessableEntity("Invalid input")
    }
  }

  def sendEmail(toUserId: Id[User], code: String) = AdminUserPage { implicit request =>
    code match {
      case "activity" => activityEmailSender(Set(toUserId))
      case _ => curator.triggerEmailToUser(code, toUserId)
    }
    NoContent
  }

  def refreshRecos(userId: Id[User]) = AdminUserPage { implicit request =>
    SafeFuture(curator.refreshUserRecos(userId), Some(s"refreshing recommendations fro $userId"))
    NoContent
  }

  def reNormalizedUsername(readOnly: Boolean, max: Int) = Action { implicit request =>
    Ok(userCommander.reNormalizedUsername(readOnly, max).toString)
  }
}
