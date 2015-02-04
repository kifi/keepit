package com.keepit.commanders.emails

import com.google.inject.{ ImplementedBy, Inject }
import com.keepit.commanders.emails.activity._
import com.keepit.commanders.{ LibraryCommander, LocalUserExperimentCommander, RecommendationsCommander }
import com.keepit.common.akka.SafeFuture
import com.keepit.common.concurrent.ReactiveLock
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.ROSession
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.mail.{ ElectronicMail, SystemEmailAddress }
import com.keepit.common.mail.template.{ TemplateOptions, EmailToSend }
import com.keepit.common.time._
import com.keepit.curator.model.{ FullUriRecoInfo, RecommendationSource, RecommendationSubSource }
import com.keepit.curator.{ CuratorServiceClient, LibraryQualityHelper }
import com.keepit.eliza.ElizaServiceClient
import com.keepit.model._
import com.keepit.social.BasicUser
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.twirl.api.Html

import scala.concurrent.Future

trait LibraryInfoView {
  def libInfo: FullLibraryInfo
  def libraryId: Id[Library]
  def name: String = libInfo.name
  def description: Option[String] = libInfo.description
  def ownerName: String = libInfo.owner.fullName
  def owner: BasicUser = libInfo.owner
  def keeps: Seq[KeepInfoView] = libInfo.keeps map KeepInfoView
  def numFollowers: Int = libInfo.numFollowers
  def numKeeps: Int = libInfo.numKeeps
  def image: Option[LibraryImageInfo] = libInfo.image
  def url: String = libInfo.url // this is not a full URL, just the /<owner>/<library> part
}

case class LibraryInfoFollowersView(view: LibraryInfoView, followers: Seq[Id[User]]) extends LibraryInfoView {
  val libInfo = view.libInfo
  val libraryId = view.libraryId
  val followerNamesToDisplay: Seq[Either[Int, Id[User]]] = {
    val followerNamesToShow = followers take 2 map Right.apply
    val otherFollowersCount = followers.size - followerNamesToShow.size
    if (otherFollowersCount == 0) followerNamesToShow
    else followerNamesToShow :+ Left(otherFollowersCount)
  }
  val followerImagesToShow = followers take 9
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
  activityComponents: Seq[Html],
  mostFollowedLibraries: Seq[LibraryInfoFollowersView],
  newKeepsInLibraries: Seq[(LibraryInfoView, Seq[KeepInfoView])],
  libraryRecos: Seq[LibraryInfoView],
  connectionRequests: Int,
  libraryInviteCount: Int,
  unreadMessageCount: Int = 0, /* todo(josh) */
  friendCreatedLibraries: Map[Id[User], Seq[LibraryInfoView]],
  friendFollowedLibraries: Seq[(Id[Library], LibraryInfoView, Seq[Id[User]])],
  newFollowersOfLibraries: Seq[LibraryInfoFollowersView])

@ImplementedBy(classOf[ActivityFeedEmailSenderImpl])
trait ActivityFeedEmailSender {
  def apply(sendTo: Set[Id[User]]): Future[Seq[ElectronicMail]]
  def apply(): Future[Unit]
}

class ActivityFeedEmailComponents @Inject() (
  val othersFollowedYourLibraryComponent: OthersFollowedYourLibraryComponent,
  val libraryRecommendations: UserLibraryRecommendationsComponent,
  val userLibraryFollowers: UserLibraryFollowersComponent,
  val unreadMessages: UserUnreadMessagesComponent,
  val requestCountComponent: ConnectionRequestCountComponent,
  val libraryInviteCountComponent: LibraryInviteCountComponent)

class ActivityFeedEmailSenderImpl @Inject() (
    val components: ActivityFeedEmailComponents,
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
    private implicit val publicIdConfig: PublicIdConfiguration) extends ActivityFeedEmailSender with ActivityEmailHelpers with Logging {

  val reactiveLock = new ReactiveLock(8)

  // max library recommendations to include in the feed
  val maxLibraryRecosInFeed = 3

  val maxActivityComponents = 5

  val maxFollowerImagesToShow = 9

  val maxOthersFollowedYourLibrary = 1

  // library recommendations to fetch from curator (doesn't mean they will all be used)
  val libRecosToFetch = 20

  def apply(sendTo: Set[Id[User]]): Future[Seq[ElectronicMail]] = {
    val emailsF = sendTo.toSeq map prepareEmailForUser map (_ flatMap emailTemplateSender.send)
    Future.sequence(emailsF)
  }

  def apply(): Future[Unit] = {
    apply(usersToSendEmailTo()) map (_ => Unit)
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

    val newFollowersOfMyLibrariesF = components.userLibraryFollowers(toUserId, previouslySentEmails, friends)
    val libRecosF = components.libraryRecommendations(toUserId, previouslySentEmails, libRecosToFetch)
    val unreadMessageThreadsF = components.unreadMessages(toUserId)
    val othersFollowedYourLibraryF = components.othersFollowedYourLibraryComponent(toUserId, previouslySentEmails, friends)
    val connectionRequestsF = components.requestCountComponent(toUserId)
    val libInviteCountF = components.libraryInviteCountComponent(toUserId)

    val newKeepsInLibrariesF = feed.getNewKeepsFromFollowedLibraries()
    val friendsWhoFollowedF = feed.getFriendsWhoFollowedLibraries(friends)
    val friendsWhoCreatedF = feed.getFriendsWhoCreatedLibraries(friends)
    val uriRecosF = feed.getUriRecommendations()

    for {
      newKeepsInLibraries <- newKeepsInLibrariesF
      unreadThreads <- unreadMessageThreadsF
      friendsWhoFollowed <- friendsWhoFollowedF
      friendsWhoCreated <- friendsWhoCreatedF
      newFollowersOfLibraries <- newFollowersOfMyLibrariesF
      uriRecos <- uriRecosF
      libRecos <- libRecosF
      othersFollowedYourLibraryRaw <- othersFollowedYourLibraryF
      connectionRequests <- connectionRequestsF
      libraryInviteCount <- libInviteCountF
    } yield {

      // get all libraries that were included in previous emails
      val librariesToExclude = previouslySentEmails.filter(_.createdAt > minRecordAge).flatMap { activityEmail =>
        activityEmail.libraryRecommendations.getOrElse(Seq.empty) ++ activityEmail.otherFollowedLibraries.getOrElse(Seq.empty)
      }.toSet

      // library recos they haven't already seen in email
      val libRecosUnseen = libRecos.filterNot(l => librariesToExclude.contains(l.libraryId))

      val othersFollowedYourLibrary = othersFollowedYourLibraryRaw filter (_.followers.size > 0) take maxOthersFollowedYourLibrary

      // sorts the library recommendations by the most growth since the last sent email for this user
      // these will be mentioned in the activity feed along with the # of followers
      val mostFollowedLibrariesRecentlyToRecommend = {
        val libIdToLibView = libRecosUnseen.map(l => l.libraryId -> l).toMap
        val libRecoIds = libRecosUnseen.map(_.libraryId)
        val libRecoMemberCountLookup = (id: Id[Library]) => libIdToLibView(id).numFollowers
        val since = lastEmailSentAt(previouslySentEmails)
        libraryCommander.sortAndSelectLibrariesWithTopGrowthSince(libRecoIds.toSet, since, libRecoMemberCountLookup).map {
          case (id, members) =>
            val memberIds = members.map(_.userId)
            LibraryInfoFollowersView(libIdToLibView(id), memberIds)
        }.take(maxActivityComponents - othersFollowedYourLibrary.size)
      }

      val libRecosAtBottom = {
        val excludeIds = mostFollowedLibrariesRecentlyToRecommend.map(_.libraryId).toSet
        libRecosUnseen filterNot { l => excludeIds.contains(l.libraryId) } take maxLibraryRecosInFeed
      }

      val activityComponents: Seq[Html] = {
        val mostFollowedHtmls = mostFollowedLibrariesRecentlyToRecommend map { view => views.html.email.v3.activityFeedOtherLibFollowersPartial(view) }
        val othersFollowedYourLibraryHtml = othersFollowedYourLibrary map { view => views.html.email.v3.activityFeedOthersFollowedYourLibraryPartial(view) }
        mostFollowedHtmls ++ othersFollowedYourLibraryHtml
      }

      val activityData = ActivityEmailData(
        userId = toUserId,
        activityComponents = activityComponents,
        mostFollowedLibraries = mostFollowedLibrariesRecentlyToRecommend,
        newKeepsInLibraries = newKeepsInLibraries,
        libraryRecos = libRecosAtBottom,
        connectionRequests = connectionRequests,
        libraryInviteCount = libraryInviteCount,
        friendCreatedLibraries = friendsWhoCreated,
        friendFollowedLibraries = friendsWhoFollowed,
        newFollowersOfLibraries = newFollowersOfLibraries
      )

      db.readWrite { implicit rw =>
        activityEmailRepo.save(ActivityEmail(
          userId = activityData.userId,
          libraryRecommendations = Some(activityData.libraryRecos.map(_.libraryId)),
          otherFollowedLibraries = Some(activityData.mostFollowedLibraries.map(_.libraryId)),
          userFollowedLibraries = Some(othersFollowedYourLibrary.map(_.libraryId))
        ))
      }

      EmailToSend(
        from = SystemEmailAddress.NOTIFICATIONS,
        to = Left(toUserId),
        subject = "Kifi Activity",
        htmlTemplate = views.html.email.v3.activityFeed(activityData),
        category = NotificationCategory.User.ACTIVITY,
        templateOptions = Seq(TemplateOptions.CustomLayout).toMap
      )
    }
  })

  class UserActivityFeedHelper(val toUserId: Id[User], val previouslySent: Seq[ActivityEmail]) extends ActivityEmailLibraryHelpers {
    val clock = ActivityFeedEmailSenderImpl.this.clock
    val libraryCommander = ActivityFeedEmailSenderImpl.this.libraryCommander

    // weight of URI reco recency... must be between 0..1
    val uriRecoRecencyWeight = 1

    // max URI recommendations to include in the feed
    val maxUriRecosToDeliver = 3

    // max number of user-followed libraries to show new keeps for
    val maxFollowedLibrariesWithNewKeeps = 10

    // max number of new keeps per user-followed libraries to show
    val maxNewKeepsPerLibrary = 10

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

      val libInfosF = createFullLibraryInfos(toUserId, libraryKeeps.map(_._1))
      libInfosF map { libInfos =>
        libInfos.zip(libraryKeeps) map {
          case ((libId, libInfo), (lib, keeps)) =>
            val keepInfos = keeps map { k => KeepInfoView(KeepInfo.fromKeep(k)) }
            BaseLibraryInfoView(libId, libInfo) -> keepInfos
        }
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

      val fullLibInfosF = createFullLibraryInfos(toUserId, libraries)
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

      val fullLibInfosF = createFullLibraryInfos(toUserId, ownerDiversifiedLibraries)

      fullLibInfosF.map { libInfos =>
        val libraryInfoViews = libInfos.map { case (libId, libInfo) => BaseLibraryInfoView(libId, libInfo) }

        libraryInfoViews.zip(ownerDiversifiedLibraries).groupBy(_._2.ownerId) map {
          case (ownerId, libInfoViews) => ownerId -> libInfoViews.map(_._1)
        }
      }
    }

    def getUriRecommendations(): Future[Seq[FullUriRecoInfo]] = {
      val recoSource = RecommendationSource.Email
      val recoSubSource = RecommendationSubSource.Unknown
      recoCommander.topRecos(toUserId, recoSource, recoSubSource, more = false, uriRecoRecencyWeight, None) map { recos =>
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
