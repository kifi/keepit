package com.keepit.commanders.emails

import com.google.inject.{ ImplementedBy, Inject }
import com.keepit.commanders.emails.activity.{ ActivityEmailDependencies, ActivityEmailLibraryHelpers, EmailUnreadThreadView, UserLibrariesComponent, UserUnreadMessagesComponent }
import com.keepit.commanders.{ LibraryCommander, LocalUserExperimentCommander, RecommendationsCommander }
import com.keepit.common.akka.SafeFuture
import com.keepit.common.concurrent.ReactiveLock
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.ROSession
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.mail.SystemEmailAddress
import com.keepit.common.mail.template.EmailToSend
import com.keepit.common.time._
import com.keepit.curator.model.{ FullUriRecoInfo, RecommendationSource, RecommendationSubSource }
import com.keepit.curator.{ CuratorServiceClient, LibraryQualityHelper }
import com.keepit.eliza.ElizaServiceClient
import com.keepit.model._
import play.api.libs.concurrent.Execution.Implicits.defaultContext

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

case class LibraryInviteInfoView(invitedByUsers: Seq[Id[User]], libraryId: Id[Library], libInfo: FullLibraryInfo) extends LibraryInfoView

case class KeepInfoView(private val keepInfo: KeepInfo) {
  private val summary = keepInfo.summary
  private val imageUrl = summary flatMap (_.imageUrl)
  private val imageWidth = summary flatMap (_.imageWidth)

  val title = keepInfo.title orElse (summary flatMap (_.title)) orElse keepInfo.siteName getOrElse keepInfo.url
  val url = keepInfo.url
  val imageUrlAndWidth: Option[(String, Int)] = imageUrl flatMap { url => imageWidth map ((url, _)) }
}

case class ActivityEmailData(
  userId: Id[User],
  newKeepsInLibraries: Seq[(LibraryInfoView, Seq[KeepInfoView])],
  libraryInvites: Seq[LibraryInviteInfoView],
  libraryRecos: Seq[LibraryInfoView],
  uriRecos: Seq[FullUriRecoInfo],
  pendingFriendRequests: Seq[Id[User]],
  friendCreatedLibraries: Map[Id[User], Seq[LibraryInfoView]],
  friendFollowedLibraries: Seq[(Id[Library], LibraryInfoView, Seq[Id[User]])],
  newFollowersOfLibraries: Seq[(LibraryInfoView, Seq[Id[User]])],
  unreadThreads: Seq[EmailUnreadThreadView])

@ImplementedBy(classOf[ActivityFeedEmailSenderImpl])
trait ActivityFeedEmailSender {
  def apply(sendTo: Set[Id[User]]): Future[Unit]
  def apply(): Future[Unit]
}

class ActivityFeedEmailSenderImpl @Inject() (
    val clock: Clock,
    val libraryCommander: LibraryCommander,
    val libraryRepo: LibraryRepo,
    val membershipRepo: LibraryMembershipRepo,
    val db: Database,
    val eliza: ElizaServiceClient,
    curator: CuratorServiceClient,
    userRepo: UserRepo,
    experimentCommander: LocalUserExperimentCommander,
    emailTemplateSender: EmailTemplateSender,
    recoCommander: RecommendationsCommander,
    libraryQualityHelper: LibraryQualityHelper,
    keepRepo: KeepRepo,
    friendRequestRepo: FriendRequestRepo,
    userConnectionRepo: UserConnectionRepo,
    activityEmailRepo: ActivityEmailRepo,
    protected val airbrake: AirbrakeNotifier,
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

  def prepareEmailForUser(toUserId: Id[User]): Future[EmailToSend] = new SafeFuture(reactiveLock.withLockFuture {
    val previouslySentEmails = db.readOnlyReplica { implicit s => activityEmailRepo.getLatestToUser(toUserId) }
    val feed = new UserActivityFeedHelper(toUserId, previouslySentEmails)

    val friends = db.readOnlyReplica { implicit session =>
      userConnectionRepo.getConnectedUsers(toUserId)
    }

    val unreadMessageThreadsF = unreadMessages(toUserId)
    val newFollowersOfMyLibrariesF = newFollowersOfUserLibraries(toUserId, previouslySentEmails)

    val newKeepsInLibrariesF = feed.getNewKeepsFromFollowedLibraries()
    val pendingLibInvitesF = feed.getPendingLibraryInvitations()
    val pendingFriendRequestsF = feed.getPendingFriendRequests()
    val friendsWhoFollowedF = feed.getFriendsWhoFollowedLibraries(friends)
    val friendsWhoCreatedF = feed.getFriendsWhoCreatedLibraries(friends)
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
        userId = toUserId,
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

      persistActivityEmail(activityData)

      EmailToSend(
        from = SystemEmailAddress.NOTIFICATIONS,
        to = Left(toUserId),
        subject = "Kifi Activity",
        htmlTemplate = views.html.email.black.activityFeed(toUserId, activityData),
        category = NotificationCategory.User.ACTIVITY
      )
    }
  })

  protected trait BaseActivityEmailDependencies extends ActivityEmailDependencies {
    private val parent = ActivityFeedEmailSenderImpl.this
    val airbrake = parent.airbrake
    val libraryCommander = parent.libraryCommander
    val libraryRepo = parent.libraryRepo
    val db = parent.db
    val membershipRepo = parent.membershipRepo
    val clock = parent.clock
    val eliza = parent.eliza
  }

  private def newFollowersOfUserLibraries(toUserId: Id[User], previouslySent: Seq[ActivityEmail]) = {
    val libraryComponent = new UserLibrariesComponent(toUserId, previouslySent) with BaseActivityEmailDependencies
    libraryComponent()
  }

  private def unreadMessages(toUserId: Id[User]) = {
    val messagesComponent = new UserUnreadMessagesComponent(toUserId) with BaseActivityEmailDependencies
    messagesComponent()
  }

  private def persistActivityEmail(activityEmailData: ActivityEmailData) = db.readWrite { implicit rw =>
    activityEmailRepo.save(ActivityEmail(
      userId = activityEmailData.userId,
      libraryRecommendations = Some(activityEmailData.libraryRecos.map(_.libraryId)),
      otherFollowedLibraries = None,
      userFollowedLibraries = None))
  }

  class UserActivityFeedHelper(val toUserId: Id[User], val previouslySent: Seq[ActivityEmail]) extends BaseActivityEmailDependencies with ActivityEmailLibraryHelpers {
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

    val libraryAgePredicate: Library => Boolean = lib => lib.createdAt > minRecordAge

    private lazy val (ownedPublishedLibs: Seq[Library], followedLibraries: Seq[Library], invitedLibraries: Seq[(LibraryInvite, Library)]) = {
      val (rawUserLibs, rawInvitedLibs) = libraryCommander.getLibrariesByUser(toUserId)
      val (ownedLibs, followedLibs) = rawUserLibs.filter(_._2.visibility == LibraryVisibility.PUBLISHED).partition(_._1.isOwner)
      (ownedLibs.map(_._2), followedLibs.map(_._2), rawInvitedLibs)
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

    def getFriendsWhoFollowedLibraries(friends: Set[Id[User]]): Future[Seq[(Id[Library], LibraryInfoView, Seq[Id[User]])]] = {
      val (libraries, libMembershipAndLibraries) = db.readOnlyReplica { implicit session =>
        val libMembershipAndLibraries = friends.toSeq flatMap { friendUserId =>
          libraryRepo.getByUser(friendUserId)
        } filter {
          case (lm, library) =>
            !lm.isOwner && library.visibility == LibraryVisibility.PUBLISHED &&
              lm.state == LibraryMembershipStates.ACTIVE && lm.lastJoinedAt.exists(minRecordAge <)
        }
        val libraries = libMembershipAndLibraries.map(_._2).distinct

        (filterAndSortLibrariesByAge(libraries), libMembershipAndLibraries)
      }

      val fullLibInfosF = createFullLibraryInfos(libraries)
      fullLibInfosF map { libInfos =>
        val membershipsByLibraryId = libMembershipAndLibraries.groupBy(_._2.id.get)
        val libIdsAndFriendUserIds: Seq[(Id[Library], (LibraryInfoView, Seq[Id[User]]))] = libInfos map {
          case (libId, libInfo) =>
            val memberships = membershipsByLibraryId(libId)
            val friendsWhoFollowThisLibrary = memberships map { case (membership, _) => membership.userId }
            val baselibInfo: LibraryInfoView = BaseLibraryInfoView(libId, libInfo)
            libId -> (baselibInfo, friendsWhoFollowThisLibrary)
        }

        // sorts libraries by # of friends following each one
        libIdsAndFriendUserIds.sortBy {
          case (_, (_, friendIds)) => -friendIds.size
        }.take(maxLibrariesFollowedByFriends).map {
          case (libId: Id[Library], (libInfoView: LibraryInfoView, followerIds: Seq[Id[User]])) =>
            (libId, libInfoView, followerIds)
        }.sortBy(-_._2.numFollowers)
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

    def getLibraryRecommendations(): Future[Seq[LibraryInfoView]] = {
      recoCommander.topPublicLibraryRecos(toUserId, libRecosToFetch, recoSource, recoSubSource) map { recos =>
        recos.take(maxLibRecostoDeliver).map { case (id, info) => BaseLibraryInfoView(id, info.itemInfo) }
      }
    } recover {
      case e: Exception =>
        airbrake.notify(s"ActivityFeedEmail Failed to load library recommendations for userId=$toUserId", e)
        Seq.empty
    }

    def getUriRecommendations(): Future[Seq[FullUriRecoInfo]] = {
      recoCommander.topRecos(toUserId, recoSource, recoSubSource, more = false, uriRecoRecencyWeight) map { recos =>
        // TODO(josh) maybe additional filtering
        recos take (maxUriRecosToDeliver)
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
