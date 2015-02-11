package com.keepit.commanders.emails

import com.google.inject.{ ImplementedBy, Inject }
import com.keepit.commanders.emails.activity._
import com.keepit.commanders.{ CropImageRequest, CroppedImageSize, KeepImageCommander, KeepsCommander, LibraryCommander, LocalUserExperimentCommander, RecommendationsCommander }
import com.keepit.common.akka.SafeFuture
import com.keepit.common.concurrent.{ FutureHelpers, ReactiveLock }
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.ROSession
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.mail.template.{ EmailToSend, TemplateOptions }
import com.keepit.common.mail.{ ElectronicMail, SystemEmailAddress }
import com.keepit.common.store.S3ImageConfig
import com.keepit.common.time._
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

case class LibraryInfoViewWithKeepImages(view: LibraryInfoView, keepImages: Seq[String]) extends LibraryInfoView {
  val libInfo = view.libInfo
  val libraryId = view.libraryId
}

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
  libraryRecos: Seq[LibraryInfoViewWithKeepImages],
  connectionRequests: Int,
  libraryInviteCount: Int,
  unreadMessageCount: Int = 0, /* todo(josh) */
  newFollowersOfLibraries: Seq[LibraryInfoFollowersView])

@ImplementedBy(classOf[ActivityFeedEmailSenderImpl])
trait ActivityFeedEmailSender {
  def apply(sendTo: Set[Id[User]]): Future[Seq[Option[ElectronicMail]]]
  def apply(): Future[Unit]
}

class ActivityFeedEmailComponents @Inject() (
  val othersFollowedYourLibraryComponent: OthersFollowedYourLibraryComponent,
  val libraryRecommendations: UserLibraryRecommendationsComponent,
  val userLibrariesByRecentFollowers: UserLibrariesByRecentFollowersComponent,
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
    keepCommander: KeepsCommander,
    keepImageCommander: KeepImageCommander,
    s3Config: S3ImageConfig,
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

  def apply(sendTo: Set[Id[User]]): Future[Seq[Option[ElectronicMail]]] = {
    val emailsF = sendTo.toSeq.map(prepareEmailForUser).map { f =>
      // transforms Future[Option[Future[_]]] into Future[Option[_]]
      val f2 = f map { _.map(emailTemplateSender.send) } map { _.map(_.map(Some.apply)).getOrElse(Future.successful(None)) }
      f2 flatMap identity
    }
    Future.sequence(emailsF)
  }

  def apply(): Future[Unit] = {
    apply(usersToSendEmailTo()) map (_ => Unit)
  }

  def usersToSendEmailTo(): Set[Id[User]] = {
    experimentCommander.getUserIdsByExperiment(ExperimentType.ACTIVITY_EMAIL)
  }

  def prepareEmailForUser(toUserId: Id[User]): Future[Option[EmailToSend]] = new SafeFuture(reactiveLock.withLockFuture {
    val previouslySentEmails = db.readOnlyReplica { implicit s => activityEmailRepo.getLatestToUser(toUserId) }

    val friends = db.readOnlyReplica { implicit session =>
      userConnectionRepo.getConnectedUsers(toUserId)
    }
    log.info(s"[activityEmail] userId=$toUserId friends=${friends.size}")

    val newFollowersOfMyLibrariesF = components.userLibrariesByRecentFollowers(toUserId, previouslySentEmails, friends)
    val libRecosF = components.libraryRecommendations(toUserId, previouslySentEmails, libRecosToFetch)
    val othersFollowedYourLibraryF = components.othersFollowedYourLibraryComponent(toUserId, previouslySentEmails, friends)
    val connectionRequestsF = components.requestCountComponent(toUserId)
    val libInviteCountF = components.libraryInviteCountComponent(toUserId)

    val emailToSendFF = for {
      newFollowersOfLibraries <- newFollowersOfMyLibrariesF
      libRecos <- libRecosF
      othersFollowedYourLibraryRaw <- othersFollowedYourLibraryF
      connectionRequests <- connectionRequestsF
      libraryInviteCount <- libInviteCountF
    } yield {
      log.info(s"[activityEmail] userId=$toUserId newFollowers $newFollowersOfLibraries")
      log.info(s"[activityEmail] userId=$toUserId libRecos $libRecos")
      log.info(s"[activityEmail] userId=$toUserId othersFollowedYourLibraryRaw $othersFollowedYourLibraryRaw")

      // get all libraries that were included in previous emails
      val librariesToExclude = previouslySentEmails.filter(_.createdAt > minRecordAge).flatMap { activityEmail =>
        activityEmail.libraryRecommendations.getOrElse(Seq.empty) ++ activityEmail.otherFollowedLibraries.getOrElse(Seq.empty)
      }.toSet

      // library recos they haven't already seen in email
      val libRecosUnseen = libRecos.filterNot(l => librariesToExclude.contains(l.libraryId))

      val othersFollowedYourLibrary = othersFollowedYourLibraryRaw filter (_.followers.size > 0) take maxOthersFollowedYourLibrary

      val mostFollowedLibrariesRecentlyToRecommend = libraryRecosForActivityFeed(previouslySentEmails, libRecosUnseen, othersFollowedYourLibrary)

      val libRecosAtBottomF = libraryRecommendationFeed(libRecosUnseen, mostFollowedLibrariesRecentlyToRecommend)

      log.info(s"[activityEmail] userId=$toUserId mostFollowedLibrariesRecentlyToRecommend $mostFollowedLibrariesRecentlyToRecommend")

      val activityComponents: Seq[Html] = {
        val mostFollowedHtmls = mostFollowedLibrariesRecentlyToRecommend map { view => views.html.email.v3.activityFeedOtherLibFollowersPartial(view) }
        val othersFollowedYourLibraryHtml = othersFollowedYourLibrary map { view => views.html.email.v3.activityFeedOthersFollowedYourLibraryPartial(view) }
        mostFollowedHtmls ++ othersFollowedYourLibraryHtml
      }

      val emailToSendOptF: Future[Option[EmailToSend]] = if (activityComponents.size < 2) {
        Future.successful(None)
      } else {
        libRecosAtBottomF.map { libRecosAtBottom =>
          log.info(s"[activityEmail] userId=$toUserId libRecosAtBottom $libRecosAtBottom")

          // do not send email without at least 2 activities and 2 lib recos (might look too empty)
          if (libRecosAtBottom.size < 2 && activityComponents.size < 2) None
          else {
            val activityData = ActivityEmailData(
              userId = toUserId,
              activityComponents = activityComponents,
              mostFollowedLibraries = mostFollowedLibrariesRecentlyToRecommend,
              libraryRecos = libRecosAtBottom,
              connectionRequests = connectionRequests,
              libraryInviteCount = libraryInviteCount,
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

            Some(EmailToSend(
              from = SystemEmailAddress.NOTIFICATIONS,
              //              to = Left(toUserId),
              to = Right(SystemEmailAddress.FEED_QA),
              subject = "Kifi Activity",
              htmlTemplate = views.html.email.v3.activityFeed(activityData),
              category = NotificationCategory.User.ACTIVITY,
              templateOptions = Seq(TemplateOptions.CustomLayout).toMap
            ))
          }
        }
      }

      emailToSendOptF
    }

    emailToSendFF flatMap identity
  })

  private def libraryRecommendationFeed(libRecosUnseen: Seq[LibraryInfoView], mostFollowedLibrariesRecentlyToRecommend: Seq[LibraryInfoFollowersView]): Future[Seq[LibraryInfoViewWithKeepImages]] = {
    val excludeIds = mostFollowedLibrariesRecentlyToRecommend.map(_.libraryId).toSet
    val libRecosUniqToEmail = libRecosUnseen filterNot { l => excludeIds.contains(l.libraryId) }

    FutureHelpers.findMatching(libRecosUniqToEmail, maxLibraryRecosInFeed, isEnoughKeepImagesForLibRecos, fetchLibToKeepImages) map { libRecosWithImages =>
      // checks if we have enough libraries with keep images; if not, use libraries w/o any
      if (libRecosWithImages.size >= maxLibraryRecosInFeed) libRecosWithImages.take(maxLibraryRecosInFeed)
      else {
        val libRecosWithImagesLibraryIds = libRecosWithImages.map(_.libraryId).toSet
        libRecosWithImages ++ libRecosUniqToEmail.filterNot(l => libRecosWithImagesLibraryIds.contains(l.libraryId)).
          take(maxLibraryRecosInFeed - libRecosWithImagesLibraryIds.size).map { libInfo =>
            LibraryInfoViewWithKeepImages(libInfo, Seq.empty)
          }
      }
    }
  }

  // sorts the library recommendations by the most growth since the last sent email for this user
  // these will be mentioned in the activity feed along with the # of followers
  private def libraryRecosForActivityFeed(previouslySentEmails: Seq[ActivityEmail], libRecosUnseen: Seq[LibraryInfoView], othersFollowedYourLibrary: Seq[LibraryInfoFollowersView]): Seq[LibraryInfoFollowersView] = {

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

  val maxKeepImagesPerLibraryReco: Int = 4

  private def isEnoughKeepImagesForLibRecos(s: LibraryInfoViewWithKeepImages) = s.keepImages.size >= maxKeepImagesPerLibraryReco
  private val keepImageCropRequest = CropImageRequest(CroppedImageSize.Small.idealSize)
  private val keepImageUrlPrefix = {
    // "urls" are paths relative to the root of the CDN
    val cdn = s3Config.cdnBase
    (if (cdn.startsWith("http")) cdn else s"http:$cdn") + "/"
  }

  // gets cropped keep images for a library
  private def fetchLibToKeepImages(libInfoView: LibraryInfoView): Future[LibraryInfoViewWithKeepImages] = {
    def recur(target: Int, offset: Int, limit: Int): Future[Seq[String]] = {
      if (target > 0) {
        val keepsF = libraryCommander.getKeeps(libInfoView.libraryId, offset, limit)

        keepsF flatMap { keeps =>
          val keepIds = keeps.map(_.id.get).toSet
          val urlsF = keepImageCommander.getBestImagesForKeepsPatiently(keepIds, keepImageCropRequest).map { keepIdsToImages =>
            keepIdsToImages.collect { case (_, Some(img)) => img.imagePath }.toSeq
          }

          urlsF flatMap { urls =>
            // if keeps.size < limit, we assume we're out of keeps in this library
            if (keeps.size == limit) recur(target - urls.size, offset + limit, limit) map { moreUrls => urls ++ moreUrls }
            else Future.successful(urls)
          }
        }
      } else Future.successful(Seq.empty)
    }

    recur(maxKeepImagesPerLibraryReco, 0, 20) map { urls =>
      LibraryInfoViewWithKeepImages(libInfoView, urls map (keepImageUrlPrefix + _))
    }
  }

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
