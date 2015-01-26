package com.keepit.commanders.emails

import com.google.inject.{ ImplementedBy, Inject }
import com.keepit.commanders.{ ProcessedImageSize, LibraryCommander, LocalUserExperimentCommander, RecommendationsCommander }
import com.keepit.common.concurrent.ReactiveLock
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.ROSession
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.mail.template.helpers._
import com.keepit.common.mail.SystemEmailAddress
import com.keepit.common.mail.template.EmailToSend
import com.keepit.common.time._
import com.keepit.curator.{ LibraryQualityHelper, CuratorServiceClient }
import com.keepit.curator.model.{ FullUriRecoInfo, FullLibRecoInfo, RecommendationSource, RecommendationSubSource }
import com.keepit.eliza.ElizaServiceClient
import com.keepit.eliza.model.{ MessageSenderNonUserView, MessageSenderView, UserThreadView, MessageView, MessageSenderUserView }
import com.keepit.model._
import org.joda.time.{ DateTime, Duration }
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.twirl.api.Html

import scala.concurrent.Future

trait LibraryInfoView {
  val libInfo: FullLibraryInfo
  val libraryId: Id[Library]
  val name = libInfo.name
  val description = libInfo.description
  var ownerName = libInfo.owner.fullName
  val keeps = libInfo.keeps map KeepInfoView
  val numFollowers = libInfo.numFollowers
  val numKeeps = libInfo.numKeeps
  val image = libInfo.image
  val url = libInfo.url
}

case class BaseLibraryInfoView(libraryId: Id[Library], libInfo: FullLibraryInfo) extends LibraryInfoView

case class LibraryInviteInfoView(invitedByUsers: Seq[Id[User]],
    libraryId: Id[Library],
    libInfo: FullLibraryInfo) extends LibraryInfoView {
}

case class KeepInfoView(private val keepInfo: KeepInfo) {
  private val summary = keepInfo.summary
  private val imageUrl = summary flatMap (_.imageUrl)
  private val imageWidth = summary flatMap (_.imageWidth)

  val title = keepInfo.title orElse (summary flatMap (_.title)) orElse keepInfo.siteName getOrElse keepInfo.url
  val url = keepInfo.url
  val imageUrlAndWidth: Option[(String, Int)] = imageUrl flatMap { url => imageWidth map ((url, _)) }
}

// TODO (josh) much of the functionality below may not be necessary per the latest design, remove unused code
// after design is final
case class EmailUnreadThreadView(private val view: UserThreadView) {
  val pageTitle = view.pageTitle
  val lastSeen = view.lastSeen
  val lastActive = view.notificationUpdatedAt
  val allMessages = view.messages

  val messageSendersToShow: Seq[Html] = {
    val senders = view.messages collect {
      case MessageView(MessageSenderUserView(id), _, _) => firstName(id)
      case MessageView(MessageSenderNonUserView(identifier), _, _) => safeHtml(identifier)
    }

    val others = senders.size - view.messages.size
    if (others > 0) senders :+ safeHtml(s"$others others")
    else senders
  }

  val firstMessageSentTime: String = {
    val msgCreatedAtPT = allMessages.head.createdAt.withZone(zones.PT)
    val nowPT = currentDateTime(zones.PT)
    val startOfTodayPT = nowPT.withTimeAtStartOfDay()
    if (startOfTodayPT < msgCreatedAtPT) "today"
    else if (startOfTodayPT.minusDays(1) < msgCreatedAtPT) "yesterday"
    else if (startOfTodayPT.minusDays(6) < msgCreatedAtPT) msgCreatedAtPT.dayOfWeek().getAsText
    else if (msgCreatedAtPT.minusDays(14) < msgCreatedAtPT) "last week"
    else s"on ${msgCreatedAtPT.monthOfYear().getAsText} ${msgCreatedAtPT.dayOfMonth().getAsText}"
  }

  val userMessages = view.messages filter {
    case MessageView(MessageSenderUserView(_), _, _) => true
    case _ => false
  }
  val totalMessageCount = view.messages.size
  val otherMessageCount = totalMessageCount - messageSendersToShow.size
}

case class ActivityEmailData(
  newKeepsInLibraries: Seq[(LibraryInfoView, Seq[KeepInfoView])],
  libraryInvites: Seq[LibraryInviteInfoView],
  libraryRecos: Seq[FullLibRecoInfo],
  uriRecos: Seq[FullUriRecoInfo],
  pendingFriendRequests: Seq[Id[User]],
  friendCreatedLibraries: Map[Id[User], Seq[LibraryInfoView]],
  friendFollowedLibraries: Map[Id[Library], (LibraryInfoView, Seq[Id[User]])],
  newFollowersOfLibraries: Seq[(LibraryInfoView, Seq[Id[User]])],
  unreadThreads: Seq[EmailUnreadThreadView])

@ImplementedBy(classOf[ActivityFeedEmailSenderImpl])
trait ActivityFeedEmailSender {
  def apply(sendTo: Set[Id[User]]): Future[Unit]
  def apply(): Future[Unit]
}

class ActivityFeedEmailSenderImpl @Inject() (
    curator: CuratorServiceClient,
    eliza: ElizaServiceClient,
    userRepo: UserRepo,
    experimentCommander: LocalUserExperimentCommander,
    emailTemplateSender: EmailTemplateSender,
    recoCommander: RecommendationsCommander,
    libraryQualityHelper: LibraryQualityHelper,
    libraryCommander: LibraryCommander,
    libraryRepo: LibraryRepo,
    membershipRepo: LibraryMembershipRepo,
    keepRepo: KeepRepo,
    friendRequestRepo: FriendRequestRepo,
    userConnectionRepo: UserConnectionRepo,
    db: Database,
    private val airbrake: AirbrakeNotifier,
    private implicit val publicIdConfig: PublicIdConfiguration) extends ActivityFeedEmailSender with Logging {

  val reactiveLock = new ReactiveLock(8)

  def apply(sendTo: Set[Id[User]]): Future[Unit] = {
    val emailsF = sendTo.toStream map prepareEmailForUser map (_ flatMap emailTemplateSender.send)
    Future.sequence(emailsF) map (_ => Unit)
  }

  def apply(): Future[Unit] = {
    apply(usersToSendEmailTo())
  }

  def usersToSendEmailTo(): Set[Id[User]] = {
    experimentCommander.getUserIdsByExperiment(ExperimentType.ACTIVITY_EMAIL)
  }

  def prepareEmailForUser(toUserId: Id[User]): Future[EmailToSend] = reactiveLock.withLockFuture {
    val feed = new UserActivityFeedHelper(toUserId)

    val friends = db.readOnlyReplica { implicit session =>
      userConnectionRepo.getConnectedUsers(toUserId)
    }

    val newKeepsInLibrariesF = feed.getNewKeepsFromFollowedLibraries()
    val unreadMessageThreadsF = feed.getUnreadMessageThreads()
    val pendingLibInvitesF = feed.getPendingLibraryInvitations()
    val pendingFriendRequestsF = feed.getPendingFriendRequests()
    val friendsWhoFollowedF = feed.getFriendsWhoFollowedLibraries(friends)
    val friendsWhoCreatedF = feed.getFriendsWhoCreatedLibraries(friends)
    val newFollowersOfMyLibrariesF = feed.getNewFollowersOfUserLibraries()
    val uriRecosF = feed.getUriRecommendations()
    val libRecosF = feed.getLibraryRecommendations()

    for {
      newKeepsInLibraries <- newKeepsInLibrariesF
      unreadThreads <- unreadMessageThreadsF
      pendingLibInvites <- pendingLibInvitesF
      pendingFriendRequests <- pendingFriendRequestsF
      friendsWhoFollowed <- friendsWhoFollowedF
      friendsWhoCreated <- friendsWhoCreatedF
      newFollowersOfLibraries <- newFollowersOfMyLibrariesF
      uriRecos <- uriRecosF
      libRecos <- libRecosF
    } yield {
      val activityData = ActivityEmailData(
        newKeepsInLibraries = newKeepsInLibraries,
        libraryInvites = pendingLibInvites map {
          case (libId, info, invites) =>
            val inviterUserIds = invites map (_.inviterId)
            LibraryInviteInfoView(inviterUserIds, libId, info)
        },
        libraryRecos = libRecos,
        uriRecos = uriRecos,
        pendingFriendRequests = pendingFriendRequests,
        friendCreatedLibraries = friendsWhoCreated,
        friendFollowedLibraries = friendsWhoFollowed,
        newFollowersOfLibraries = newFollowersOfLibraries,
        unreadThreads = unreadThreads
      )

      EmailToSend(
        from = SystemEmailAddress.NOTIFICATIONS,
        to = Left(toUserId),
        subject = "Kifi Activity",
        htmlTemplate = views.html.email.black.activityFeed(toUserId, activityData),
        category = NotificationCategory.User.ACTIVITY
      )
    }
  }

  class UserActivityFeedHelper(val toUserId: Id[User]) {

    val recoSource = RecommendationSource.Email
    val recoSubSource = RecommendationSubSource.Unknown

    // weight of URI reco recency... must be between 0..1
    val uriRecoRecencyWeight = 1

    // max URI recommendations to include in the feed
    val maxUriRecosToDeliver = 3

    // max library recommendations to include in the feed
    val maxLibRecostoDeliver = 3

    // library recommendations to fetch from curator
    val libRecosToFetch = maxLibRecostoDeliver * 2

    // max number of user-followed libraries to show new keeps for
    val maxFollowedLibrariesWithNewKeeps = 10

    // max number of new keeps per user-followed libraries to show
    val maxNewKeepsPerLibrary = 10

    // max number of pending invited-to libraries to display
    val maxInvitedLibraries = 10

    // max number of friend requests to display
    val maxFriendRequests = 10

    // max number of libraries to show created by friends
    val maxFriendsWhoCreatedLibraries = 10

    // max number of libraries to show that were followed by friends
    val maxLibrariesFollowedByFriends = 10

    val maxNewFollowersOfLibraries = 10

    val maxNewFollowersOfLibrariesUsers = 10

    val minAgeOfUnreadNotificationThreads = currentDateTime.minusWeeks(6)
    val maxAgeOfUnreadNotificationThreads = currentDateTime.minusHours(12)

    val maxUnreadNotificationThreads = 10

    val minRecordAge = currentDateTime.minus(Duration.standardDays(7))

    val libraryAgePredicate: Library => Boolean = lib => lib.createdAt > minRecordAge

    private lazy val (ownedLibs: Seq[Library], followedLibraries: Seq[Library], invitedLibraries: Seq[(LibraryInvite, Library)]) = {
      val (rawUserLibs, rawInvitedLibs) = libraryCommander.getLibrariesByUser(toUserId)
      val (ownedLibs, followedLibs) = rawUserLibs.partition(_._1.isOwner)
      (ownedLibs.map(_._2), followedLibs.map(_._2), rawInvitedLibs)
    }

    protected def createFullLibraryInfos(libraries: Seq[Library]) = {
      libraryCommander.createFullLibraryInfos(viewerUserIdOpt = Some(toUserId),
        showPublishedLibraries = true, maxKeepsShown = 10,
        maxMembersShown = 0, idealKeepImageSize = ProcessedImageSize.Large.idealSize,
        idealLibraryImageSize = ProcessedImageSize.Large.idealSize,
        libraries = libraries, withKeepTime = true)
    }

    def getNewFollowersOfUserLibraries(): Future[Seq[(LibraryInfoView, Seq[Id[User]])]] = {
      val librariesToMembers = db.readOnlyReplica { implicit session =>
        ownedLibs map { library =>
          val members = membershipRepo.getWithLibraryId(library.id.get) filter { membership =>
            membership.state == LibraryMembershipStates.ACTIVE && !membership.isOwner &&
              membership.lastJoinedAt.exists(minRecordAge <)
          } sortBy (-_.lastJoinedAt.map(_.getMillis).getOrElse(0L))
          (library, members take maxNewFollowersOfLibrariesUsers)
        } take maxNewFollowersOfLibraries
      }

      val libraries = db.readOnlyReplica { implicit session =>
        val libraries = librariesToMembers.map(_._1)
        filterAndSortLibrariesByAge(libraries)
      }

      val librariesToMembersMap = librariesToMembers.map {
        case (lib, members) => (lib.id.get, members)
      }.toMap

      val libInfosF = createFullLibraryInfos(libraries)
      libInfosF map { libInfos =>
        libInfos map {
          case (libId, libInfo) =>
            val members = librariesToMembersMap(libId) map (_.userId)
            val libInfoView = BaseLibraryInfoView(libId, libInfo)
            (libInfoView, members)
        }
      }
    }

    def getNewKeepsFromFollowedLibraries(): Future[Seq[(LibraryInfoView, Seq[KeepInfoView])]] = {
      val libraryKeeps = db.readOnlyReplica { implicit session =>
        followedLibraries map { library =>
          val keeps = keepRepo.getByLibrary(library.id.get, 0, maxNewKeepsPerLibrary) filter { keep =>
            keep.createdAt > minRecordAge
          }
          library -> keeps
        } filter (_._2.nonEmpty) take maxFollowedLibrariesWithNewKeeps
      }

      val libInfosF = createFullLibraryInfos(libraryKeeps.map(_._1))
      libInfosF map { libInfos =>
        libInfos.zip(libraryKeeps) map {
          case ((libId, libInfo), (lib, keeps)) =>
            val keepInfos = keeps map { k => KeepInfoView(KeepInfo.fromKeep(k)) }
            BaseLibraryInfoView(libId, libInfo) -> keepInfos
        }
      }
    }

    def getUnreadMessageThreads(): Future[Seq[EmailUnreadThreadView]] = {
      eliza.getUnreadNotifications(toUserId, maxUnreadNotificationThreads) map { userThreads =>
        userThreads filter { thread =>
          val threadLastActive = thread.notificationUpdatedAt
          threadLastActive > minAgeOfUnreadNotificationThreads &&
            threadLastActive < maxAgeOfUnreadNotificationThreads &&
            thread.messages.nonEmpty && thread.messages.head.from.kind != MessageSenderView.SYSTEM
        } take maxUnreadNotificationThreads map EmailUnreadThreadView
      }
    }

    def getPendingLibraryInvitations(): Future[Seq[(Id[Library], FullLibraryInfo, Seq[LibraryInvite])]] = {
      val libraries = invitedLibraries map (_._2) groupBy (_.id) map (_._2.head) toSeq
      // groupBy().map().toSeq above is a hack around duplicate library invites from the same user (which is/was possible and should be cleaned up)
      val invitesByLibraryId = invitedLibraries map (_._1) groupBy (_.libraryId)
      val fullLibraryInfosF = createFullLibraryInfos(libraries)

      fullLibraryInfosF map { fullLibraryInfos =>
        fullLibraryInfos map {
          case (libraryId, fullLibInfo) => (libraryId, fullLibInfo, invitesByLibraryId(libraryId))
        } take maxInvitedLibraries
      }
    } recover {
      case e: Exception =>
        airbrake.notify(e)
        Seq.empty
    }

    def getPendingFriendRequests(): Future[Seq[Id[User]]] = {
      db.readOnlyReplicaAsync { implicit session =>
        friendRequestRepo.getByRecipient(userId = toUserId, states = Set(FriendRequestStates.ACTIVE))
      } map {
        _ sortBy (-_.updatedAt.getMillis) map (_.senderId) take maxFriendRequests
      }
    }

    def getFriendsWhoFollowedLibraries(friends: Set[Id[User]]): Future[Map[Id[Library], (LibraryInfoView, Seq[Id[User]])]] = {
      val (libraries, libMembershipAndLibraries) = db.readOnlyReplica { implicit session =>
        val libMembershipAndLibraries = friends.toSeq flatMap { friendUserId =>
          libraryRepo.getByUser(friendUserId)
        } filter {
          case (lm, library) =>
            !lm.isOwner && library.visibility == LibraryVisibility.PUBLISHED &&
              lm.state == LibraryMembershipStates.ACTIVE && lm.lastJoinedAt.exists(minRecordAge <)
        }
        val libraries = libMembershipAndLibraries.map(_._2)

        (filterAndSortLibrariesByAge(libraries), libMembershipAndLibraries)
      }

      val fullLibInfosF = createFullLibraryInfos(libraries)
      fullLibInfosF map { libInfos =>
        val membershipsByLibraryId = libMembershipAndLibraries.groupBy(_._2.id.get)
        val libIdsAndFriendUserIds = libInfos map {
          case (libId, libInfo) =>
            val memberships = membershipsByLibraryId(libId)
            val friendsWhoFollowThisLibrary = memberships map { case (membership, _) => membership.userId }
            libId -> (BaseLibraryInfoView(libId, libInfo), friendsWhoFollowThisLibrary)
        }

        // sorts libraries by # of friends following each one
        libIdsAndFriendUserIds.sortBy {
          case (_, (_, friends)) => -friends.size
        }.take(maxLibrariesFollowedByFriends).toMap
      }
    }

    def getFriendsWhoCreatedLibraries(friends: Set[Id[User]]): Future[Map[Id[User], Seq[LibraryInfoView]]] = {
      val libraries = db.readOnlyReplica { implicit session =>
        filterAndSortLibrariesByAge(libraryRepo.getAllByOwners(friends), libraryAgePredicate)
      }

      // groups libraries by owner to "score" each library based on how many other libraries are in the collection
      // from the same owner, then sorts all of the libraries by the score to promote selecting libraries owned by
      // different users
      val ownerDiversifiedLibraries = libraries.groupBy(_.ownerId).map { case (_, libs) => libs.zipWithIndex }.
        toSeq.flatten.sortBy(_._2).take(maxFriendsWhoCreatedLibraries).map(_._1)

      val fullLibInfosF = createFullLibraryInfos(ownerDiversifiedLibraries)

      fullLibInfosF.map { libInfos =>
        val libraryInfoViews = libInfos.map { case (libId, libInfo) => BaseLibraryInfoView(libId, libInfo) }

        libraryInfoViews.zip(ownerDiversifiedLibraries).groupBy(_._2.ownerId) map {
          case (ownerId, libInfoViews) => ownerId -> libInfoViews.map(_._1)
        }
      }
    }

    def getLibraryRecommendations(): Future[Seq[FullLibRecoInfo]] = {
      recoCommander.topPublicLibraryRecos(toUserId, libRecosToFetch, recoSource, recoSubSource) map { recos =>
        // TODO(josh) maybe additional filtering
        recos.take(maxLibRecostoDeliver)
      }
    } recover {
      case e: Exception =>
        airbrake.notify(s"ActivityFeedEmail Failed to load library recommendations for userId=$toUserId", e)
        Seq.empty
    }

    def getUriRecommendations(): Future[Seq[FullUriRecoInfo]] = {
      recoCommander.topRecos(toUserId, recoSource, recoSubSource, more = false, uriRecoRecencyWeight) map { recos =>
        // TODO(josh) maybe additional filtering
        recos.take(maxUriRecosToDeliver)
      }
    } recover {
      case e: Exception =>
        airbrake.notify(s"ActivityFeedEmail Failed to load uri recommendations for userId=$toUserId", e)
        Seq.empty
    }

    /*
     * filters out non-published libraries, libraries with "bad" names, libraries with zero keeps,
     * and libraries that don't pass the optional predicate argument
     *
     * returns libraries sorted by date created descending
     */
    protected def filterAndSortLibrariesByAge(libraries: Seq[Library], predicate: Library => Boolean = _ => true)(implicit db: ROSession) = {
      val onceFilteredLibraries = libraries filter { library =>
        library.visibility == LibraryVisibility.PUBLISHED &&
          !libraryQualityHelper.isBadLibraryName(library.name) &&
          predicate(library)
      }

      val libraryIds = onceFilteredLibraries.map(_.id.get).toSet
      val libraryStats = libraryCommander.getBasicLibraryStatistics(libraryIds)

      // filter out libraries with 0 keeps
      onceFilteredLibraries.
        filter { lib => libraryStats.get(lib.id.get).map(_.keepCount).getOrElse(0) > 0 }.
        sortBy(-_.createdAt.getMillis)
    }
  }
}
