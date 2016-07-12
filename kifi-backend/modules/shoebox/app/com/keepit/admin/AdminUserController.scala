package com.keepit.controllers.admin

import com.google.inject.Inject
import com.keepit.abook.ABookServiceClient
import com.keepit.abook.model.{ OrganizationUserMayKnow, RichContact }
import com.keepit.commanders._
import com.keepit.commanders.emails.ActivityFeedEmailSender
import com.keepit.common.akka.{ SlowRunningExecutionContext, SafeFuture }
import com.keepit.common.concurrent.{ ChunkedResponseHelper, FutureHelpers }
import com.keepit.common.controller._
import com.keepit.common.db._
import com.keepit.common.core.mapExtensionOps
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.db.slick._
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.SlackLog
import com.keepit.common.mail._
import com.keepit.common.service.IpAddress
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.store.S3ImageStore
import com.keepit.common.time._
import com.keepit.common.util.{ LinkElement, DescriptionElements }
import com.keepit.eliza.ElizaServiceClient
import com.keepit.eliza.model.UserThreadStats
import com.keepit.heimdal._
import com.keepit.model.{ KeepToCollection, UserExperiment, _ }
import com.keepit.search.SearchServiceClient
import com.keepit.slack.{ InhouseSlackClient, InhouseSlackChannel, SlackClientWrapper, SlackClient }
import com.keepit.slack.models._
import com.keepit.social.{ BasicUser, SocialGraphPlugin, SocialId, SocialNetworks, SocialUserRawInfoStore }
import com.keepit.typeahead.{ KifiUserTypeahead, SocialUserTypeahead, TypeaheadHit }
import org.joda.time.Minutes
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.json._
import play.api.mvc.{ Action, AnyContent, Result }
import views.html

import scala.collection.mutable
import scala.concurrent.duration._
import scala.concurrent.{ Await, Future, Promise }
import scala.util.{ Failure, Success, Try }

case class InvitationInfo(activeInvites: Seq[Invitation], acceptedInvites: Seq[Invitation])

case class UserStatisticsPage(
    userViewType: UserViewType,
    users: Seq[UserStatistics],
    usersOnline: Map[Id[User], Boolean],
    userThreadStats: Map[Id[User], Future[UserThreadStats]],
    page: Int,
    userCount: Int,
    pageSize: Int,
    newUsers: Option[Int],
    recentUsers: Seq[Id[User]] = Seq.empty,
    invitationInfo: Option[InvitationInfo] = None) {

  def getUserThreadStats(user: User): UserThreadStats = Await.result(userThreadStats(user.id.get), 5 seconds)
}

sealed trait UserViewType
object UserViewTypes {
  case object All extends UserViewType
  case object TopKeepersNotInOrg extends UserViewType
  case object Registered extends UserViewType
  case object Fake extends UserViewType
  case class ByExperiment(exp: UserExperimentType) extends UserViewType
  case object UsersPotentialOrgs extends UserViewType
  case object LinkedInUsersWithoutOrgs extends UserViewType
}
import com.keepit.controllers.admin.UserViewTypes._

class AdminUserController @Inject() (
    val userActionsHelper: UserActionsHelper,
    db: Database,
    clock: Clock,
    slackChannelToLibraryRepo: SlackChannelToLibraryRepo,
    slackClient: SlackClientWrapper,
    userRepo: UserRepo,
    socialUserInfoRepo: SocialUserInfoRepo,
    normalizedURIRepo: NormalizedURIRepo,
    mailRepo: ElectronicMailRepo,
    socialUserRawInfoStore: SocialUserRawInfoStore,
    keepRepo: KeepRepo,
    keepMutator: KeepMutator,
    ktlRepo: KeepToLibraryRepo,
    ktuRepo: KeepToUserRepo,
    orgRepo: OrganizationRepo,
    orgMembershipRepo: OrganizationMembershipRepo,
    orgMembershipCandidateRepo: OrganizationMembershipCandidateRepo,
    orgInviteRepo: OrganizationInviteRepo,
    socialConnectionRepo: SocialConnectionRepo,
    searchFriendRepo: SearchFriendRepo,
    userConnectionRepo: UserConnectionRepo,
    kifiInstallationRepo: KifiInstallationRepo,
    emailRepo: UserEmailAddressRepo,
    userEmailAddressCommander: UserEmailAddressCommander,
    userExperimentRepo: UserExperimentRepo,
    socialGraphPlugin: SocialGraphPlugin,
    searchClient: SearchServiceClient,
    userValueRepo: UserValueRepo,
    collectionRepo: CollectionRepo,
    keepToCollectionRepo: KeepToCollectionRepo,
    keepTagRepo: KeepTagRepo,
    heimdalContextBuilder: HeimdalContextBuilderFactory,
    libraryRepo: LibraryRepo,
    libraryCommander: LibraryCommander,
    libraryMembershipRepo: LibraryMembershipRepo,
    libraryInviteRepo: LibraryInviteRepo,
    invitationRepo: InvitationRepo,
    userSessionRepo: UserSessionRepo,
    imageStore: S3ImageStore,
    userPictureRepo: UserPictureRepo,
    basicUserRepo: BasicUserRepo,
    userCredRepo: UserCredRepo,
    handleRepo: HandleOwnershipRepo,
    handleCommander: HandleCommander,
    userCommander: UserCommander,
    socialUserTypeahead: SocialUserTypeahead,
    kifiUserTypeahead: KifiUserTypeahead,
    eliza: ElizaServiceClient,
    abookClient: ABookServiceClient,
    heimdal: HeimdalServiceClient,
    activityEmailSender: ActivityFeedEmailSender,
    userIpAddressCommander: UserIpAddressCommander,
    userStatisticsCommander: UserStatisticsCommander,
    typeAheadCommander: TypeaheadCommander,
    slackTeamMembershipRepo: SlackTeamMembershipRepo,
    slackTeamRepo: SlackTeamRepo,
    tagCommander: TagCommander,
    twitterPublishingCommander: TwitterPublishingCommander,
    airbrake: AirbrakeNotifier,
    private implicit val inhouseSlackClient: InhouseSlackClient) extends AdminUserActions with PaginationActions {

  val slackLog = new SlackLog(InhouseSlackChannel.TEST_CAM)

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
        val updatedSocialUser = {
          if (su.networkType == SocialNetworks.FORTYTWO) su.withState(SocialUserInfoStates.INACTIVE)
          else su.withUser(toUser)
        }
        socialUserInfoRepo.save(updatedSocialUser)
      }
      userRepo.save(toUser.withState(UserStates.ACTIVE))
      userRepo.save(fromUser.withState(UserStates.INACTIVE))

      userConnectionRepo.deactivateAllConnections(fromUserId)

      userSessionRepo.invalidateByUser(fromUserId)
    }

    for (su <- db.readOnlyReplica { implicit s => socialUserInfoRepo.getByUser(toUserId) }) {
      try { socialGraphPlugin.asyncFetch(su) } catch { case e: Exception => log.error(s"while merging $fromUserId to $toUserId", e) }
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
    val userId = user.id.get
    val chatStatsF = eliza.getUserThreadStats(userId)
    val abookInfoF = abookClient.getABookInfos(userId)
    val econtactCountF = abookClient.getEContactCount(userId)

    val (experiments, potentialOrganizations, ignoreForPotentialOrganizations) = db.readOnlyReplica { implicit s =>
      val ignore4orgs = userValueRepo.getValue(userId, UserValues.ignoreForPotentialOrganizations)
      val potentialOrganizationsForUser = if (!ignore4orgs) orgRepo.getPotentialOrganizationsForUser(userId).filter(_.state == OrganizationStates.ACTIVE) else Seq.empty
      (
        userExperimentRepo.getUserExperiments(user.id.get),
        potentialOrganizationsForUser,
        ignore4orgs)
    }

    val fOrgRecos = if (ignoreForPotentialOrganizations) Future.successful(Seq.empty) else try {
      abookClient.getOrganizationRecommendationsForUser(user.id.get, offset = 0, limit = 5)
    } catch {
      case ex: Exception => airbrake.notify(ex); Future.successful(Seq.empty[OrganizationUserMayKnow])
    }

    val fullUserStatisticsF = userStatisticsCommander.fullUserStatistics(userId)

    val slackInfoF = db.readOnlyReplicaAsync { implicit s =>
      val memberships = slackTeamMembershipRepo.getByUserId(userId)
      val teamsById = slackTeamRepo.getBySlackTeamIds(memberships.map(_.slackTeamId).toSet)
      memberships.map(membership => (teamsById(membership.slackTeamId), membership))
    }
    val usersOnlineF = eliza.areUsersOnline(Seq(userId))

    for {
      fullUserStatistics <- fullUserStatisticsF
      abookInfos <- abookInfoF
      econtactCount <- econtactCountF
      orgRecos <- fOrgRecos
      chatStats <- chatStatsF
      slackInfo <- slackInfoF
      usersOnline <- usersOnlineF
    } yield {
      val recommendedOrgs = db.readOnlyReplica { implicit session => orgRecos.map(reco => (orgRepo.get(reco.orgId), reco.score * 10000)).filter(_._1.state == OrganizationStates.ACTIVE) }
      Ok(html.admin.user(user, chatStats, usersOnline(userId), fullUserStatistics, experiments, abookInfos, econtactCount,
        potentialOrganizations, ignoreForPotentialOrganizations, recommendedOrgs, slackInfo))
    }
  }

  private def doUserKeepsView(userId: Id[User], showPrivates: Boolean)(implicit request: UserRequest[AnyContent]): Result = {
    if (showPrivates) {
      log.warn(s"${request.user.firstName} ${request.user.firstName} (${request.userId}) is viewing user $userId's private keeps and contacts")
    }

    val (user, bookmarks) = db.readOnlyReplica { implicit s =>
      val user = userRepo.get(userId)
      val keepIds = {
        val allKeepIds = ktuRepo.getAllByUserId(userId).map(_.keepId).toSet
        if (showPrivates) allKeepIds else {
          ktlRepo.getAllByKeepIds(allKeepIds).filterValues(_.exists(!_.isPrivate)).keySet
        }
      }
      val bookmarks = keepRepo.getActiveByIds(keepIds).values
      val uris = bookmarks map (_.uriId) map normalizedURIRepo.get
      (user, (bookmarks, uris).zipped.toList.seq)
    }

    val form = request.request.body.asFormUrlEncoded.map { req => req.map(r => (r._1 -> r._2.head)) }

    val bookmarkSearch = form.flatMap { _.get("bookmarkSearch") }
    val filteredBookmarks = db.readOnlyReplica { implicit s =>
      val query = bookmarkSearch.getOrElse("").toLowerCase()
      (if (query.trim.length == 0) {
        bookmarks
      } else {
        bookmarks.filter { case (b, u) => b.title.exists { t => t.toLowerCase().indexOf(query) >= 0 } }
      }) collect {
        case (mark, uri) =>
          val colls = tagCommander.getTagsForKeep(mark.id.get)
          (mark, uri, colls)
      }
    }

    Ok(html.admin.userKeeps(user, bookmarks.size, filteredBookmarks, bookmarkSearch))
  }

  def bulkMessageUsers() = AdminUserPage { implicit request =>
    Ok(html.admin.bulkMessage())
  }

  def userJsonByEitherId(id: String) = AdminUserAction { implicit request =>
    db.readOnlyReplica { implicit s =>
      Try(id.toLong).map { userId =>
        userRepo.get(Id[User](userId))
      }.toOption.orElse {
        ExternalId.asOpt[User](id).flatMap { userExtId =>
          userRepo.getOpt(userExtId)
        }
      }.map { user =>
        val orgMemberships = orgMembershipRepo.getAllByUserId(user.id.get)
        val orgOpt = orgRepo.getByIds(orgMemberships.map(_.organizationId).toSet).values.toList
          .filter(_.state == OrganizationStates.ACTIVE)
          .map { org =>
            (org, orgMemberships.find(_.organizationId == org.id.get).get)
          }.sortBy { c =>
            (c._2.role, -c._2.id.get.id)
          }.map(_._1).headOption

        Ok(Json.obj(
          "user" -> user,
          "basic" -> basicUserRepo.load(user.id.get),
          "org" -> orgOpt
        ))
      }.getOrElse {
        NotFound(Json.obj("error" -> "no_clue_who_this_is"))
      }
    }
  }

  def allUsersView = usersView(0)
  def allRegisteredUsersView = registeredUsersView(0)
  def allFakeUsersView = fakeUsersView(0)
  def allUsersPotentialOrgsView = usersPotentialOrgsView(0)
  def allLinkedInUsersWithoutOrgsView = linkedInUsersWithoutOrgsView(0)

  def userStatisticsPage(userViewType: UserViewType, page: Int = 0, pageSize: Int = 30): Future[UserStatisticsPage] = {
    val usersF = Future {
      db.readOnlyReplica { implicit s =>
        userViewType match {
          case All => (userRepo.pageIncluding(UserStates.ACTIVE)(page, pageSize),
            userRepo.countIncluding(UserStates.ACTIVE))
          case TopKeepersNotInOrg =>
            val users = userRepo.topKeepersNotInOrgs(40)
            (users, users.size)
          case Registered => (userRepo.pageIncludingWithoutExp(UserStates.ACTIVE)(UserExperimentType.FAKE, UserExperimentType.AUTO_GEN)(page, pageSize),
            userRepo.countIncludingWithoutExp(UserStates.ACTIVE)(UserExperimentType.FAKE, UserExperimentType.AUTO_GEN))
          case Fake => (userRepo.pageIncludingWithExp(UserStates.ACTIVE)(UserExperimentType.FAKE, UserExperimentType.AUTO_GEN)(page, pageSize),
            userRepo.countIncludingWithExp(UserStates.ACTIVE)(UserExperimentType.FAKE, UserExperimentType.AUTO_GEN))
          case ByExperiment(exp) => (userRepo.pageIncludingWithExp(UserStates.ACTIVE)(exp)(page, pageSize),
            userRepo.countIncludingWithExp(UserStates.ACTIVE)(exp))
          case UsersPotentialOrgs =>
            (
              userRepo.pageUsersWithPotentialOrgs(page, pageSize),
              userRepo.countUsersWithPotentialOrgs()
            )
          case LinkedInUsersWithoutOrgs =>
            (
              userRepo.pageLinkedInUsersWithoutOrgs(page, pageSize),
              userRepo.countLinkedInUsersWithoutOrgs()
            )
        }
      }
    }

    val userStatsF = usersF.map {
      case (users, userCount) =>
        db.readOnlyReplica { implicit s =>
          val socialUserInfos = socialUserInfoRepo.getByUsers(users.map(_.id.get)).groupBy(_.userId.get)
          (users.map(u => userStatisticsCommander.userStatistics(u, socialUserInfos)), userCount)
        }
    }

    val userThreadStatsF = usersF.map {
      case (users, _) => (users.map(u => (u.id.get -> eliza.getUserThreadStats(u.id.get)))).seq.toMap
    }

    val usersOnlineF = usersF.map {
      case (users, _) => eliza.areUsersOnline(users.map(_.id.get))
    }

    val (newUsers, recentUsers, inviteInfo) = userViewType match {
      case Registered =>
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
        val usersOnline = Await.result(Await.result(usersOnlineF, Duration.Inf), Duration.Inf)
        UserStatisticsPage(userViewType, users, usersOnline, userThreadStats, page, userCount, pageSize, newUsers, recentUsers, inviteInfo)
    }
  }

  def usersView(page: Int = 0) = AdminUserPage.async { implicit request =>
    userStatisticsPage(All, page).map { p => Ok(html.admin.users(p, None)) }
  }

  def registeredUsersView(page: Int = 0) = AdminUserPage.async { implicit request =>
    userStatisticsPage(Registered, page).map { p => Ok(html.admin.users(p, None)) }
  }

  def fakeUsersView(page: Int = 0) = AdminUserPage.async { implicit request =>
    userStatisticsPage(Fake, page).map { p => Ok(html.admin.users(p, None)) }
  }

  def usersPotentialOrgsView(page: Int = 0) = AdminUserPage.async { implicit request =>
    userStatisticsPage(UsersPotentialOrgs, page).map { p => Ok(html.admin.users(p, None)) }
  }

  def topKeepersNotInOrg() = AdminUserPage.async { implicit request =>
    userStatisticsPage(TopKeepersNotInOrg, 0).map { p => Ok(html.admin.users(p, None)) }
  }

  def linkedInUsersWithoutOrgsView(page: Int = 0) = AdminUserPage.async { implicit request =>
    userStatisticsPage(LinkedInUsersWithoutOrgs, page).map { p => Ok(html.admin.users(p, None)) }
  }

  def createLibrary(userId: Id[User]) = AdminUserPage(parse.tolerantFormUrlEncoded) { implicit request =>
    val nameOpt = request.body.get("name").flatMap(_.headOption)
    val visibilityOpt = request.body.get("visibility").flatMap(_.headOption).map(LibraryVisibility(_))
    val slugOpt = request.body.get("slug").flatMap(_.headOption)

    (nameOpt, visibilityOpt) match {
      case (Some(name), Some(visibility)) =>
        val libraryAddRequest = LibraryInitialValues(name, visibility, slugOpt)

        implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.Site).build
        val result: Either[LibraryFail, Library] = libraryCommander.createLibrary(libraryAddRequest, userId)
        result match {
          case Left(fail) => BadRequest(fail.message)
          case Right(_) => Ok
        }
      case _ => BadRequest("All Fields are required.")
    }
  }

  def byExperimentUsersView(page: Int, exp: String) = AdminUserPage.async { implicit request =>
    userStatisticsPage(ByExperiment(UserExperimentType(exp)), page).map { p => Ok(html.admin.users(p, None)) }
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
          userIds.map(userRepo.get).map(u => userStatisticsCommander.userStatistics(u, socialUserInfos))
        }
        val userThreadStats = (users.par.map { u =>
          val userId = u.user.id.get
          (userId -> eliza.getUserThreadStats(u.user.id.get))
        }).seq.toMap
        val usersOnlineF = eliza.areUsersOnline(userIds)
        val usersOnline = Await.result(usersOnlineF, Duration.Inf)
        Ok(html.admin.users(UserStatisticsPage(All, users, usersOnline, userThreadStats, 0, users.size, users.size, None), searchTerm))
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

      // Intern required emails
      emailList map { address =>
        log.info("Interning email address %s to userId %s".format(address, userId.id.toString))
        userEmailAddressCommander.intern(userId, address).get
      }

      // Deactivate other emails
      oldEmails.filterNot(email => emailList.contains(email.address)) foreach { removedEmail =>
        log.info("Removing email address %s from userId %s".format(removedEmail.address, userId.id.toString))
        userEmailAddressCommander.deactivate(removedEmail, force = true).get
      }
    }

    Redirect(com.keepit.controllers.admin.routes.AdminUserController.userView(userId))
  }

  //todo: this code may become hard to maintain, should be unified with the production path
  def connectUsers(user1: Id[User]) = AdminUserPage { implicit request =>
    val user2 = Id[User](request.body.asFormUrlEncoded.get.apply("user2").head.toLong)
    db.readWrite { implicit session =>
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

  def addExperimentAction(userId: Id[User], experiment: String) = AdminUserAction { request =>
    addExperiment(requesterUserId = request.userId, userId, experiment) match {
      case Right(expType) => Ok(Json.obj(experiment -> true))
      case Left(s) => Forbidden
    }
  }

  def addExperimentForUsers(experiment: String) = AdminUserAction { request =>
    val userIds = request.body.asText.get.split(",").map(id => Id[User](id.trim.toLong))
    val successIds = userIds map { userId =>
      addExperiment(requesterUserId = request.userId, userId, experiment) match {
        case Right(expType) => Some(userId)
        case Left(s) => None
      }
    }
    Ok(Json.obj(experiment -> successIds.flatten.toSeq))
  }

  def isSuperAdmin(userId: Id[User]) = {
    val SUPER_ADMIN_SET: Set[Id[User]] = Set(Id[User](1), Id[User](3))
    SUPER_ADMIN_SET contains userId
  }

  def isAdminExperiment(expType: UserExperimentType) = expType == UserExperimentType.ADMIN

  def addExperiment(requesterUserId: Id[User], userId: Id[User], experiment: String): Either[String, UserExperimentType] = {
    val expType = UserExperimentType.get(experiment)
    if (isAdminExperiment(expType) && !isSuperAdmin(requesterUserId)) {
      Left("Failure")
    } else {
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
      Right(expType)
    }
  }

  def removeExperimentAction(userId: Id[User], experiment: String) = AdminUserAction { request =>
    removeExperiment(requesterUserId = request.userId, userId, experiment) match {
      case Right(expType) => Ok(Json.obj(experiment -> false))
      case Left(s) => Forbidden
    }
  }

  def removeExperiment(requesterUserId: Id[User], userId: Id[User], experiment: String): Either[String, UserExperimentType] = {
    val expType = UserExperimentType(experiment)
    if (isAdminExperiment(expType) && !isSuperAdmin(requesterUserId)) {
      Left("Failure")
    } else {
      db.readWrite { implicit session =>
        val ue: Option[UserExperiment] = userExperimentRepo.get(userId, UserExperimentType(experiment))
        ue foreach { ue =>
          userExperimentRepo.save(ue.withState(UserExperimentStates.INACTIVE))
          val experiments = userExperimentRepo.getUserExperiments(userId)
          eliza.sendToUser(userId, Json.arr("experiments", experiments.map(_.value)))
          heimdal.setUserProperties(userId, "experiments" -> ContextList(experiments.map(exp => ContextStringData(exp.value)).toSeq))
          userRepo.save(userRepo.getNoCache(userId)) // update user index sequence number
        }
      }
      Right(expType)
    }
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

  def bumpUserSeq() = AdminUserPage { implicit request =>
    Ok("No, I refuse.")
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
      //      val allUsers = db.readOnlyReplica { implicit s => userRepo.all }
      //      allUsers.foreach(user => heimdal.deleteUser(user.id.get))
      Ok("No I refuse")
    }
  }

  def resetAllMixpanelProfiles() = AdminUserPage.async { implicit request =>
    SafeFuture {
      //      val allUsers = db.readOnlyReplica { implicit s => userRepo.all }
      //      allUsers.foreach(doResetMixpanelProfile)
      Ok("No I refuse")
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
        properties += ("$email", emailRepo.getByUser(userId).address)
        properties += ("state", user.state.value)
        properties += ("userId", user.id.get.id)
        properties += ("admin", "https://admin.kifi.com" + com.keepit.controllers.admin.routes.AdminUserController.userView(user.id.get).url)

        val keepVisibilityCount = ktlRepo.getPrivatePublicCountByUser(userId)
        val keeps = keepVisibilityCount.all
        properties += ("keeps", keeps)
        properties += ("publicKeeps", keepVisibilityCount.published + keepVisibilityCount.discoverable + keepVisibilityCount.organization)
        properties += ("privateKeeps", keepVisibilityCount.secret)
        properties += ("tags", tagCommander.getCountForUser(userId))
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
      //      val conns = db.readOnlyReplica { implicit s =>
      //        userConnectionRepo.all()
      //      }
      //
      //      conns.grouped(100).foreach { cs =>
      //        db.readWrite { implicit s =>
      //          cs.foreach { c => userConnectionRepo.save(c) }
      //        }
      //      }
      //
      //      val friends = db.readOnlyReplica { implicit s =>
      //        searchFriendRepo.all()
      //      }
      //
      //      friends.grouped(100).foreach { fs =>
      //        db.readWrite { implicit s =>
      //          fs.foreach { f => searchFriendRepo.save(f) }
      //        }
      //      }
      Ok("No I refuse")
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

  def deactivate(userId: Id[User]) = AdminUserPage.async { request =>
    SafeFuture {
      val doIt = request.body.asFormUrlEncoded.get.get("doIt").exists(_.head == "true")
      val json = db.readWrite { implicit session =>
        if (doIt) {
          deleteAllUserData(userId)
        }

        val user = userRepo.get(userId)
        val emails = emailRepo.getAllByUser(userId)
        val credentials = userCredRepo.findByUserIdOpt(userId)
        val installations = kifiInstallationRepo.all(userId)
        val tags = tagCommander.tagsForUser(userId, 0, 200, TagSorting.NumKeeps).map(_.name)
        val keeps = keepRepo.getByUser(userId)
        val slackMemberships = slackTeamMembershipRepo.getByUserId(userId).map(m => s"${m.slackTeamId.value}|${m.slackUserId.value}")
        val socialUsers = socialUserInfoRepo.getByUser(userId)
        val socialConnections = socialConnectionRepo.getSocialConnectionInfosByUser(userId)
        val userConnections = userConnectionRepo.getConnectedUsers(userId)
        val handles = handleRepo.getByOwnerId(Some(userId)).map(_.handle)
        implicit val userIdFormat = Id.format[User]
        Json.obj(
          "user" -> user,
          "usernames" -> handles,
          "emails" -> emails.map(_.address),
          "credentials" -> credentials.map(_.credentials),
          "installations" -> JsObject(installations.map(installation => installation.userAgent.name -> JsString(installation.version.toString))),
          "tags" -> tags,
          "keeps" -> keeps,
          "slackMemberships" -> slackMemberships,
          "socialUsers" -> socialUsers,
          "socialConnections" -> JsObject(socialConnections.toSeq.map { case (network, connections) => network.name -> Json.toJson(connections) }),
          "userConnections" -> userConnections
        )
      }
      Ok(json)
    }
  }

  private def deleteAllUserData(userId: Id[User])(implicit session: RWSession): Unit = {
    val toBeCleanedUp = userRepo.get(userId)
    if (toBeCleanedUp.state != UserStates.INACTIVE) throw new IllegalArgumentException(s"Failed to delete user data - Watch out, this user is not inactive!!! - $toBeCleanedUp")

    // todo(Léo): this procedure is incomplete (e.g. does not deal with ABook or Eliza), and should probably be moved to UserCommander and unified with AutoGen Reaper

    // Social Graph
    userConnectionRepo.deactivateAllConnections(userId) // User Connections
    socialUserInfoRepo.getByUser(userId).foreach { sui =>
      socialConnectionRepo.deactivateAllConnections(sui.id.get) // Social Connections
      invitationRepo.getByRecipientSocialUserId(sui.id.get).foreach(invitation => invitationRepo.save(invitation.withState(InvitationStates.INACTIVE)))
      socialUserInfoRepo.save(sui.withState(SocialUserInfoStates.INACTIVE).copy(userId = None, credentials = None, username = None, socialId = SocialId(ExternalId[Nothing]().id))) // Social User Infos
      socialUserInfoRepo.deleteCache(sui)
    }

    // Slack
    slackTeamMembershipRepo.getByUserId(userId).foreach(slackTeamMembershipRepo.deactivate)

    // URI Graph
    keepRepo.getByUser(userId).foreach(keepMutator.deactivateKeep)
    ktuRepo.getAllByUserId(userId).foreach(ktuRepo.deactivate)
    collectionRepo.getUnfortunatelyIncompleteTagsByUser(userId).foreach { collection =>
      keepToCollectionRepo.getByCollection(collection.id.get).foreach { ktc =>
        keepToCollectionRepo.save(ktc.sanitizeForDelete)
      }
      collectionRepo.save(collection.copy(state = CollectionStates.INACTIVE))
    }
    keepTagRepo.getAllByUser(userId).foreach { kt => keepTagRepo.deactivate(kt) }

    // Libraries Data
    libraryInviteRepo.getByUser(userId, Set(LibraryInviteStates.INACTIVE)).foreach { case (invite, _) => libraryInviteRepo.save(invite.withState(LibraryInviteStates.INACTIVE)) } // Library Invites
    libraryMembershipRepo.getWithUserId(userId).foreach { membership => libraryMembershipRepo.save(membership.withState(LibraryMembershipStates.INACTIVE)) } // Library Memberships
    val ownedLibraries = libraryRepo.getAllByOwner(userId).map(_.id.get)
    val ownsCollaborativeLibs = libraryMembershipRepo.getCollaboratorsByLibrary(ownedLibraries.toSet).exists { case (_, collaborators) => collaborators.size > 1 }
    assert(!ownsCollaborativeLibs, "cannot deactivate a user if they own a library with collaborators: either delete the library or transfer its ownership")
    FutureHelpers.sequentialExec(ownedLibraries)(libraryCommander.unsafeAsyncDeleteLibrary)

    // Personal Info
    userSessionRepo.invalidateByUser(userId) // User Session
    kifiInstallationRepo.all(userId).foreach { installation => kifiInstallationRepo.save(installation.withState(KifiInstallationStates.INACTIVE)) } // Kifi Installations
    userCredRepo.findByUserIdOpt(userId).foreach { userCred => userCredRepo.save(userCred.copy(state = UserCredStates.INACTIVE)) } // User Credentials
    emailRepo.getAllByUser(userId).foreach { email => emailRepo.save(email.withState(UserEmailAddressStates.INACTIVE)) } // Email addresses

    // Organizations Data
    orgMembershipRepo.getAllByUserId(userId).foreach { membership => orgMembershipRepo.save(membership.withState(OrganizationMembershipStates.INACTIVE)) }
    orgInviteRepo.getAllByUserId(userId).foreach { invite => orgInviteRepo.save(invite.withState(OrganizationInviteStates.INACTIVE)) }
    orgMembershipCandidateRepo.getAllByUserId(userId).foreach { candidacy => orgMembershipCandidateRepo.save(candidacy.withState(OrganizationMembershipCandidateStates.INACTIVE)) }
    assert(orgRepo.getAllByOwnerId(userId).isEmpty, "cannot deactivate a user if they own an org: either delete the org or transfer its ownership")

    val user = userRepo.get(userId)

    userRepo.save(user.withState(UserStates.INACTIVE).copy(primaryUsername = None)) // User
    handleCommander.reclaimAll(userId, overrideProtection = true, overrideLock = true)
  }

  def setUsername(userId: Id[User]) = AdminUserPage { request =>
    val username: Option[String] = request.body.asFormUrlEncoded.flatMap(_.get("username").flatMap(_.headOption)).filter(_.length > 0)
    username.map { newUsername =>
      userCommander.setUsername(userId, Username(newUsername.trim), overrideValidityCheck = true, overrideProtection = true) match {
        case Right(_) => Ok
        case Left(err) => BadRequest(err)
      }
    }.getOrElse(BadRequest("No username provided"))
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

  def userIpAddressesView(ownerId: Id[User]) = AdminUserPage { implicit request =>
    val owner = db.readOnlyReplica { implicit session => userRepo.get(ownerId) }
    val logs: Seq[UserIpAddress] = userIpAddressCommander.getByUser(ownerId, 100)
    val sharedIpAddresses: Map[IpAddress, Seq[Id[User]]] = userIpAddressCommander.findSharedIpsByUser(ownerId, 100)
    val pages: Map[IpAddress, Set[(User, Option[EmailAddress], Set[Organization])]] = sharedIpAddresses.map { case (ip, userIds) => ip -> usersAndOrgs(userIds) }.toMap
    Ok(html.admin.userIpAddresses(owner, logs, pages))
  }

  private def usersAndOrgs(userIds: Seq[Id[User]]): Set[(User, Option[EmailAddress], Set[Organization])] = {
    db.readOnlyReplica { implicit s =>
      val users = userRepo.getAllUsers(userIds).values.toSet
      val emailAddresses = userIds.map { userId => userId -> Try(emailRepo.getByUser(userId)).toOption }.toMap
      users map { user =>
        val orgsCandidates = orgMembershipCandidateRepo.getByUserId(user.id.get, Limit(10000), Offset(0)).map(_.organizationId).toSet
        val orgMembers = orgMembershipRepo.getByUserId(user.id.get, Limit(10000), Offset(0)).map(_.organizationId).toSet
        (user, emailAddresses.get(user.id.get).flatten, orgRepo.getByIds(orgsCandidates ++ orgMembers).values.toSet)
      }
    }
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
      case _ => throw new UnsupportedOperationException(code)
    }
    NoContent
  }

  def reNormalizedUsername(readOnly: Boolean, max: Int) = AdminUserAction { implicit request =>
    Ok(userCommander.reNormalizedUsername(readOnly, max).toString)
  }

  def setIgnoreForPotentialOrganizations(userId: Id[User]) = AdminUserPage(parse.tolerantFormUrlEncoded) { implicit request =>
    val ignorePotentialOrgs = request.body.get("ignorePotentialOrgs").isDefined
    db.readWrite { implicit session =>
      userValueRepo.setValue(userId, UserValueName.IGNORE_FOR_POTENTIAL_ORGANIZATIONS, ignorePotentialOrgs)
    }
    Redirect(routes.AdminUserController.userView(userId))
  }

  def hideOrganizationRecoForUser(userId: Id[User], orgId: Id[Organization]) = AdminUserPage { request =>
    abookClient.hideOrganizationRecommendationForUser(userId, orgId)
    Redirect(com.keepit.controllers.admin.routes.AdminUserController.userView(userId))
  }

  def flushClients(id: Id[User]) = AdminUserPage.async { implicit request =>
    eliza.flush(id).map(_ => Ok)
  }

  //return true if at least one of its slack users is active
  def slackUserOnline(id: Id[User]) = AdminUserPage.async { implicit request =>
    val memberships = db.readOnlyReplica { implicit s => slackTeamMembershipRepo.getByUserId(id) }
    FutureHelpers.exists(memberships) { membership =>
      val presence = slackClient.checkUserPresence(membership.slackTeamId, membership.slackUserId)
      presence.foreach(prez => log.info(s"found presence info $prez for membership ${membership.id.get} or slack user ${membership.slackUsername} team ${membership.slackUserId} "))
      presence.map(_.state == SlackUserPresenceState.Active).recover {
        case error: Throwable =>
          log.error(s"error fetching presence using ${membership.id.get} for slack user ${membership.slackUsername} team ${membership.slackUserId} with scopes ${membership.scopes}", error)
          false
      }
    } map { active => Ok(JsBoolean(active)) }
  }

  //will kill slackUserOnline for this one after it works fine
  def slackUserPresence(id: Id[User]) = AdminUserPage.async { implicit request =>
    val (memberships, slackTeamById) = db.readOnlyReplica { implicit s =>
      val memberships = slackTeamMembershipRepo.getByUserId(id)
      val slackTeamById = slackTeamRepo.getBySlackTeamIds(memberships.map(_.slackTeamId).toSet)
      (memberships, slackTeamById)
    }
    val presencesF = memberships.map { membership =>
      slackClient.checkUserPresence(membership.slackTeamId, membership.slackUserId).recover {
        case error: Throwable =>
          log.error(s"error fetching presence using ${membership.id.get} for slack user ${membership.slackUsername} team ${membership.slackUserId} with scopes ${membership.scopes}", error)
          SlackUserPresence(SlackUserPresenceState.ERROR, None, JsNull)
      } map { p =>
        membership -> p
      }
    }
    Future.sequence(presencesF).map { presences =>
      val presencesJson = presences.map {
        case (membership, presence) =>
          JsObject.apply(Seq(
            "user" -> JsString(membership.slackUsername.map(_.value) getOrElse membership.slackUserId.value),
            "slackUserId" -> JsString(membership.slackUserId.value),
            "team" -> JsString(slackTeamById(membership.slackTeamId).slackTeamName.value),
            "state" -> JsString(presence.state.name),
            "origJson" -> presence.originalJson,
            "since" -> (presence.lastActivity.map { date =>
              val minutes = Minutes.minutesBetween(date, clock.now())
              JsString(s"${minutes.getMinutes}M")
            }).getOrElse(JsNull)))
      }
      Ok(JsArray.apply(presencesJson))
    }
  }

  def suggestRecipient(userId: Id[User], query: Option[String], limit: Option[Int], drop: Option[Int], requested: Option[String]) = AdminUserAction.async { request =>
    val requestedSet = requested.map(_.split(",").map(_.trim).flatMap(TypeaheadRequest.applyOpt).toSet).filter(_.nonEmpty).getOrElse(TypeaheadRequest.all)
    typeAheadCommander.searchAndSuggestKeepRecipients(userId, query.getOrElse(""), limit, drop, requestedSet).map { suggestions =>
      val body = suggestions.take(limit.getOrElse(20)).collect {
        case u: UserContactResult => Json.toJson(u)
        case e: EmailContactResult => Json.toJson(e)
        case l: LibraryResult => Json.toJson(l)
      }
      Ok(Json.toJson(body))
    }
  }

  def backfillTags(startPage: Int, endPage: Int, doItForReal: Boolean) = AdminUserAction { implicit request =>
    SafeFuture {
      (startPage to endPage).foreach { page =>
        val collectionById = mutable.Map.empty[Id[Collection], Collection]
        db.readWrite { implicit session =>
          val grp = keepToCollectionRepo.pageAscending(page, 2000, Set(KeepToCollectionStates.INACTIVE))
            .groupBy(_.keepId)
          val keepIds = grp.keys.toSet
          val existingTags = keepTagRepo.getByKeepIds(keepIds).mapValues(_.map(_.tag.normalized))
          val keeps = keepRepo.getActiveByIds(keepIds)

          val cnts = grp.map {
            case (keepId, ktcs) =>
              val existing = existingTags.getOrElse(keepId, Seq.empty).toSet
              val newTags = ktcs.filter { ktc =>
                val coll = collectionById.getOrElseUpdate(ktc.collectionId, collectionRepo.get(ktc.collectionId))
                !existing.contains(coll.name.normalized) && coll.isActive && keeps.get(keepId).isDefined
              }.map { ktc =>
                val coll = collectionById.getOrElseUpdate(ktc.collectionId, collectionRepo.get(ktc.collectionId))
                (coll.userId, coll.name, ktc.createdAt)
              }

              if (doItForReal) {
                newTags.map {
                  case (userId, tag, createdAt) =>
                    keepTagRepo.save(KeepTag(createdAt = createdAt, tag = tag, keepId = keepId, messageId = None, userId = Some(userId)))
                }
              }
              newTags.length
          }

          log.info(s"[backfillTags] $page: ${cnts.sum} new tags on ${grp.size} keeps")
        }
      }
    }(SlowRunningExecutionContext.ec)

    Ok("going!")
  }

  def announceToAllUsers() = AdminUserAction(parse.tolerantJson) { implicit request =>
    val userIds = Set(1L, 3L, 61L, 35713L, 98082L).map(Id[User])

    val chunkSize = 100
    val nUsers = userIds.size // db.readOnlyMaster(implicit s => userRepo.count)
    val numChunks = nUsers / chunkSize

    val enum = ChunkedResponseHelper.chunkedFuture(0 to numChunks) { chunk =>
      //val userIds = db.readOnlyMaster(implicit s => userRepo.pageAscendingIds(chunk, chunkSize, excludeStates = UserStates.ALL - UserStates.ACTIVE))
      eliza.sendAnnouncementToUsers(userIds).map { _ =>
        s"sent to ${userIds.headOption}-${userIds.lastOption}"
      }
    }

    Ok.chunked(enum)
  }

  def sendWindDownSlackDM() = AdminUserAction.async(parse.tolerantJson) { implicit request =>
    val dryRun = (request.body \ "dryRun").asOpt[Boolean].getOrElse(true)
    val userId = (request.body \ "userId").asOpt[Id[User]]
    val fromId = (request.body \ "fromId").asOpt[Id[SlackTeamMembership]]

    val exportUrl = "https://www.kifi.com/keepmykeeps"
    val blogPostUrl = "https://medium.com/@kifi/f1cd2f2e116c"
    val message = {
      import DescriptionElements._
      val msgText = DescriptionElements(
        "The Kifi team is joining Google", "(learn more)" --> LinkElement(blogPostUrl), "! The service will be fully operational for just a few more weeks.",
        "Please visit", "Kifi.com/keepmykeeps" --> LinkElement(exportUrl), "on your desktop to export all of your data within Kifi.",
        "You can email support@kifi.com with questions."
      )
      SlackMessageRequest.fromKifi(formatForSlack(msgText))
    }

    val stms = db.readOnlyMaster { implicit request =>
      //slackTeamMembershipRepo.getMembershipsOfKifiUsersWhoHaventExported(fromId) // ~4000 max
      userId.map(slackTeamMembershipRepo.getByUserId).getOrElse(Seq.empty)
    }

    FutureHelpers.sequentialExec(stms.grouped(100).toSeq) { chunk =>
      val messageFut = if (!dryRun) {
        Future.sequence(chunk.map { stm =>
          slackClient.sendToSlackHoweverPossible(stm.slackTeamId, stm.slackUserId.asChannel, message)
            .recover {
              case fail =>
                slackLog.warn(s"failed to send to ${stm.userId} ${stm.id.get}")
                Future.successful(())
            }
        })
      } else Future.successful(())
      messageFut.map(_ => slackLog.info(s"sent to ${chunk.headOption.map(_.id.get)}-${chunk.lastOption.map(_.id.get)}"))
    }.map { _ => Ok("done") }
  }
}
