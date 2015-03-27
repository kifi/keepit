package com.keepit.shoebox

import java.util.concurrent.atomic.AtomicInteger

import com.google.inject.util.Providers
import com.keepit.common.actor.FakeScheduler
import com.keepit.common.db._
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.mail.template.EmailToSend
import com.keepit.common.mail.{ ElectronicMail, EmailAddress }
import com.keepit.common.net.URI
import com.keepit.common.service.ServiceType
import com.keepit.common.store.ImageSize
import com.keepit.common.time._
import com.keepit.common.usersegment.UserSegment
import com.keepit.common.zookeeper.ServiceCluster
import com.keepit.model._
import com.keepit.model.view.{ LibraryMembershipView, UserSessionView }
import com.keepit.rover.document.utils.Signature
import com.keepit.rover.fetcher.HttpRedirect
import com.keepit.scraper.ScrapeRequest
import com.keepit.search._
import com.keepit.shoebox.model.ids.UserSessionExternalId
import com.keepit.social.{ BasicUser, SocialId, SocialNetworkType }
import org.joda.time.DateTime
import play.api.libs.json.JsObject

import scala.collection.mutable
import scala.collection.mutable.{ Map => MutableMap }
import scala.concurrent.Future
import com.keepit.common.crypto.PublicIdConfiguration

class FakeShoeboxScraperClientImpl(val airbrakeNotifier: AirbrakeNotifier) extends ShoeboxScraperClient {
  val serviceCluster: ServiceCluster = new ServiceCluster(ServiceType.TEST_MODE, Providers.of(airbrakeNotifier), new FakeScheduler(), () => {})

  protected def httpClient: com.keepit.common.net.HttpClient = ???

  def assignScrapeTasks(zkId: Long, max: Int): Future[Seq[ScrapeRequest]] = {
    Future.successful(Seq.empty[ScrapeRequest])
  }

  def getUriImage(nUriId: Id[NormalizedURI]): Future[Option[String]] = Future.successful(Some("http://www.adummyurl.com"))

  def getAllURLPatterns(): Future[UrlPatternRules] = Future.successful(UrlPatternRules(Seq.empty))

  def saveScrapeInfo(info: ScrapeInfo): Future[Unit] = ???

  def saveNormalizedURI(uri: NormalizedURI): Future[NormalizedURI] = ???

  def updateNormalizedURIState(uriId: Id[NormalizedURI], state: State[NormalizedURI]): Future[Unit] = ???

  def updateNormalizedURI(uriId: => Id[NormalizedURI],
    createdAt: => DateTime,
    updatedAt: => DateTime,
    externalId: => ExternalId[NormalizedURI],
    title: => Option[String],
    url: => String,
    urlHash: => UrlHash,
    state: => State[NormalizedURI],
    seq: => SequenceNumber[NormalizedURI],
    screenshotUpdatedAt: => Option[DateTime],
    restriction: => Option[Restriction],
    normalization: => Option[Normalization],
    redirect: => Option[Id[NormalizedURI]],
    redirectTime: => Option[DateTime]): Future[Unit] = Future.successful(Unit)

  def recordPermanentRedirect(uri: NormalizedURI, redirect: HttpRedirect): Future[NormalizedURI] = ???

  def recordScrapedNormalization(uriId: Id[NormalizedURI], signature: Signature, candidateUrl: String, candidateNormalization: Normalization, alternateUrls: Set[String]): Future[Unit] = ???

  def getProxy(url: String): Future[Option[HttpProxy]] = ???

  def getProxyP(url: String): Future[Option[HttpProxy]] = ???

  def getLatestKeep(url: String): Future[Option[Keep]] = ???

}
// code below should be sync with code in ShoeboxController
class FakeShoeboxServiceClientImpl(val airbrakeNotifier: AirbrakeNotifier, implicit val publicIdConfig: PublicIdConfiguration) extends ShoeboxServiceClient {
  val serviceCluster: ServiceCluster = new ServiceCluster(ServiceType.TEST_MODE, Providers.of(airbrakeNotifier), new FakeScheduler(), () => {})
  protected def httpClient: com.keepit.common.net.HttpClient = ???

  // Fake ID counters

  private val userIdCounter = new AtomicInteger(0)
  private def nextUserId() = { Id[User](userIdCounter.incrementAndGet()) }

  private val bookmarkIdCounter = new AtomicInteger(0)
  private def nextBookmarkId() = { Id[Keep](bookmarkIdCounter.incrementAndGet()) }

  private val uriIdCounter = new AtomicInteger(0)
  private def nextUriId() = { Id[NormalizedURI](uriIdCounter.incrementAndGet()) }

  private val urlIdCounter = new AtomicInteger(0)
  private def nextUrlId() = { Id[URL](urlIdCounter.incrementAndGet()) }

  private val collectionIdCounter = new AtomicInteger(0)
  private def nextCollectionId() = { Id[Collection](collectionIdCounter.incrementAndGet()) }

  private val searchExpIdCounter = new AtomicInteger(0)
  private def nextSearchExperimentId() = { Id[SearchConfigExperiment](searchExpIdCounter.incrementAndGet()) }

  private val userExpIdCounter = new AtomicInteger(0)
  private def nextUserExperimentId() = { Id[UserExperiment](userExpIdCounter.incrementAndGet()) }

  private val userConnIdCounter = new AtomicInteger(0)
  private def nextUserConnId = Id[UserConnection](userConnIdCounter.incrementAndGet())

  private val searchFriendIdCounter = new AtomicInteger(0)
  private def nextSearchFriendId = Id[SearchFriend](searchFriendIdCounter.incrementAndGet())

  private val libraryIdCounter = new AtomicInteger(0)
  private def nextLibraryId = Id[Library](libraryIdCounter.incrementAndGet())

  private val libraryMembershipIdCounter = new AtomicInteger(0)
  private def nextLibraryMembershipId = Id[LibraryMembership](libraryMembershipIdCounter.incrementAndGet())

  // Fake sequence counters

  private val userSeqCounter = new AtomicInteger(0)
  private def nextUserSeqNum() = SequenceNumber[User](userSeqCounter.incrementAndGet())

  private val uriSeqCounter = new AtomicInteger(0)
  private def nextUriSeqNum() = { SequenceNumber[NormalizedURI](uriSeqCounter.incrementAndGet()) }

  private val bookmarkSeqCounter = new AtomicInteger(0)
  private def nextBookmarkSeqNum() = { SequenceNumber[Keep](bookmarkSeqCounter.incrementAndGet()) }

  private val collectionSeqCounter = new AtomicInteger(0)
  private def nextCollectionSeqNum() = { SequenceNumber[Collection](collectionSeqCounter.incrementAndGet()) }

  private val userConnSeqCounter = new AtomicInteger(0)
  private def nextUserConnSeqNum() = SequenceNumber[UserConnection](userConnSeqCounter.incrementAndGet())

  private val searchFriendSeqCounter = new AtomicInteger(0)
  private def nextSearchFriendSeqNum() = SequenceNumber[SearchFriend](searchFriendSeqCounter.incrementAndGet())

  private val librarySeqCounter = new AtomicInteger(0)
  private def nextLibrarySeq() = SequenceNumber[Library](librarySeqCounter.incrementAndGet())

  private val libraryMembershipSeqCounter = new AtomicInteger(0)
  private def nextLibraryMembershipSeq() = SequenceNumber[LibraryMembership](libraryMembershipSeqCounter.incrementAndGet())

  // Fake repos

  val allUsers = MutableMap[Id[User], User]()
  val allUserExternalIds = MutableMap[ExternalId[User], User]()
  val allUserConnections = MutableMap[Id[User], Set[Id[User]]]()
  val allUserImageUrls = MutableMap[Id[User], String]()
  val allConnections = MutableMap[Id[UserConnection], UserConnection]()
  val allSearchFriends = MutableMap[Id[SearchFriend], SearchFriend]()
  val allUserExperiments = MutableMap[Id[User], Set[UserExperiment]]()
  val allProbabilisticExperimentGenerators = MutableMap[Name[ProbabilisticExperimentGenerator], ProbabilisticExperimentGenerator]()
  val allUserBookmarks = MutableMap[Id[User], Set[Id[Keep]]]().withDefaultValue(Set.empty)
  val allBookmarks = MutableMap[Id[Keep], Keep]()
  val allNormalizedURIs = MutableMap[Id[NormalizedURI], NormalizedURI]()
  val uriToUrl = MutableMap[Id[NormalizedURI], URL]()
  val allCollections = MutableMap[Id[Collection], Collection]()
  val allCollectionBookmarks = MutableMap[Id[Collection], Set[Id[Keep]]]()
  val allSearchExperiments = MutableMap[Id[SearchConfigExperiment], SearchConfigExperiment]()
  val allUserEmails = MutableMap[Id[User], Set[EmailAddress]]()
  val allUserValues = MutableMap[(Id[User], UserValueName), String]()
  val allUserFriendRequests = MutableMap[Id[User], Seq[Id[User]]]()
  val sentMail = mutable.MutableList[ElectronicMail]()
  val uriSummaries = MutableMap[Id[NormalizedURI], URISummary]()
  val socialUserInfosByUserId = MutableMap[Id[User], List[SocialUserInfo]]()
  val allLibraries = MutableMap[Id[Library], Library]()
  val allLibraryMemberships = MutableMap[Id[LibraryMembership], LibraryMembership]()
  val newKeepsInLibrariesExpectation = MutableMap[Id[User], Seq[Keep]]()

  // Track service client calls

  val callsGetToCandidateURIs = mutable.ArrayBuffer[Seq[Id[NormalizedURI]]]()

  // Fake data initialization methods

  def saveUsers(users: User*): Seq[User] = {
    users.map { user =>
      val id = user.id.getOrElse(nextUserId())
      val updatedUser = user.withId(id).copy(seq = nextUserSeqNum)
      allUsers(id) = updatedUser
      allUserExternalIds(updatedUser.externalId) = updatedUser
      log.info(s"saving user $user into allUserExternalIds $allUserExternalIds")
      updatedUser
    }
  }

  def saveURIs(uris: NormalizedURI*): Seq[NormalizedURI] = synchronized {
    uris.map { uri =>
      val id = uri.id.getOrElse(nextUriId())
      val updatedUri = uri.withId(id).copy(seq = nextUriSeqNum())
      val updatedUrl = uriToUrl.getOrElse(id, URLFactory(url = updatedUri.url, normalizedUriId = updatedUri.id.get).withId(nextUrlId()))
      allNormalizedURIs(id) = updatedUri
      uriToUrl(id) = updatedUrl
      updatedUri
    }
  }

  def saveURISummary(uriId: Id[NormalizedURI], uriSummary: URISummary): Unit = synchronized {
    uriSummaries(uriId) = uriSummary
  }

  def saveUserImageUrl(id: Int, url: String) = synchronized {
    allUserImageUrls(Id[User](id)) = url
  }

  def saveConnections(connections: Map[Id[User], Set[Id[User]]]) {
    // use directed edges to generate undirected edges
    val edges = connections.map { case (u, fs) => fs.map { f => Array((u, f), (f, u)) }.flatten }.flatten.toSet
    edges.groupBy(_._1).map { case (u, fs) => allUserConnections(u) = fs.map { _._2 } }
    edges.foreach {
      case (u1, u2) =>
        if (u1.id < u2.id) {
          val conn = getConnection(u1, u2)
          if (conn.isEmpty) {
            val id = nextUserConnId
            val seq = nextUserConnSeqNum
            allConnections(id) = UserConnection(id = Some(id), user1 = u1, user2 = u2, seq = seq)
          } else {
            val c = conn.get
            if (c.state != UserConnectionStates.ACTIVE) {
              allConnections(c.id.get) = c.copy(state = UserConnectionStates.ACTIVE, seq = nextUserConnSeqNum())
            }
          }
        }
    }
  }

  def getConnection(user1: Id[User], user2: Id[User]): Option[UserConnection] = {
    allConnections.map { case (id, c) => if (Set(user1, user2) == Set(c.user1, c.user2)) Some(c) else None }.flatten.headOption
  }

  def deleteConnections(connections: Map[Id[User], Set[Id[User]]]) {
    val edges = connections.map { case (u, fs) => fs.map { f => Array((u, f), (f, u)) }.flatten }.flatten.toSet
    edges.groupBy(_._1).map { case (u, fs) => allUserConnections(u) = allUserConnections.getOrElse(u, Set.empty) -- fs.map { _._2 } }

    val pairs = connections.map { case (uid, friends) => friends.map { f => (uid, f) } }.flatten.toSet
    pairs.map {
      case (u1, u2) =>
        getConnection(u1, u2).foreach { c =>
          allConnections(c.id.get) = c.copy(state = UserConnectionStates.UNFRIENDED, seq = nextUserConnSeqNum)
        }
    }
  }

  def clearUserConnections(userIds: Id[User]*) {
    userIds.map { id =>
      if (allUserConnections.get(id).isDefined) {
        allUserConnections(id).foreach { friend =>
          getConnection(id, friend).foreach { conn =>
            allConnections(conn.id.get) = conn.copy(state = UserConnectionStates.INACTIVE, seq = nextUserConnSeqNum)
            allUserConnections(friend) = allUserConnections.getOrElse(friend, Set.empty) - id
          }
        }
      }
      allUserConnections(id) = Set[Id[User]]()
    }
  }

  def excludeFriend(userId: Id[User], friendId: Id[User]) {
    allSearchFriends.values.filter(x => x.userId == userId && x.friendId == friendId).headOption match {
      case Some(r) if (r.state != SearchFriendStates.EXCLUDED) => allSearchFriends(r.id.get) = r.copy(state = SearchFriendStates.EXCLUDED, seq = nextSearchFriendSeqNum)
      case None => val id = nextSearchFriendId; allSearchFriends(id) = SearchFriend(id = Some(id), userId = userId, friendId = friendId, seq = nextSearchFriendSeqNum)
    }
  }

  def saveBookmarks(bookmarks: Keep*): Seq[Keep] = {
    bookmarks.map { b =>
      val id = b.id.getOrElse(nextBookmarkId())
      val updatedBookmark = b.withId(id).copy(seq = nextBookmarkSeqNum())
      allBookmarks(id) = updatedBookmark
      allUserBookmarks(b.userId) = allUserBookmarks.getOrElse(b.userId, Set.empty) + id
      updatedBookmark
    }
  }

  def saveCollections(collections: Collection*): Seq[Collection] = {
    collections.map { c =>
      val id = c.id.getOrElse(nextCollectionId())
      val updatedCollection = c.withId(id).copy(seq = nextCollectionSeqNum())
      allCollections(id) = updatedCollection
      updatedCollection
    }
  }

  def saveBookmarksToCollection(collectionId: Id[Collection], bookmarks: Keep*) {
    allCollectionBookmarks(collectionId) = allCollectionBookmarks.getOrElse(collectionId, Set.empty) ++ bookmarks.map(_.id.get)
    allCollections(collectionId) = allCollections(collectionId).copy(seq = nextCollectionSeqNum())
  }

  private def internLibrary(userId: Id[User], isPrivate: Boolean): Library = {
    val visibility = Keep.isPrivateToVisibility(isPrivate)
    allLibraries.values.find(library => library.ownerId == userId && library.visibility == visibility) getOrElse {
      val name = if (isPrivate) "Private Library" else "Main Library"
      val slug = LibrarySlug(if (isPrivate) "private" else "main")
      val library = Library(name = name, ownerId = userId, visibility = visibility, slug = slug, memberCount = 0)
      val libraryId = saveLibraries(library).head.id.get
      val membership = LibraryMembership(libraryId = libraryId, userId = userId, access = LibraryAccess.OWNER)
      saveLibraryMemberships(membership)
      allLibraries(libraryId)
    }
  }

  def saveBookmarksByEdges(edges: Seq[(NormalizedURI, User, Option[String])], isPrivate: Boolean = false, source: KeepSource = KeepSource("fake")): Seq[Keep] = {
    val bookmarks = edges.map {
      case (uri, user, optionalTitle) =>
        val library = internLibrary(user.id.get, isPrivate)
        val url = uriToUrl(uri.id.get)
        Keep(title = optionalTitle orElse uri.title, userId = user.id.get, uriId = uri.id.get, urlId = url.id.get, url = url.url, source = source, visibility = library.visibility, libraryId = Some(library.id.get), inDisjointLib = library.isDisjoint)
    }
    saveBookmarks(bookmarks: _*)
  }

  def saveBookmarksByURI(edgesByURI: Seq[(NormalizedURI, Seq[User])], uniqueTitle: Option[String] = None, isPrivate: Boolean = false, source: KeepSource = KeepSource("fake")): Seq[Keep] = {
    val edges = for ((uri, users) <- edgesByURI; user <- users) yield (uri, user, uniqueTitle)
    saveBookmarksByEdges(edges, isPrivate, source)
  }

  def saveBookmarksByUser(edgesByUser: Seq[(User, Seq[NormalizedURI])], uniqueTitle: Option[String] = None, isPrivate: Boolean = false, source: KeepSource = KeepSource("fake")): Seq[Keep] = {
    val edges = for ((user, uris) <- edgesByUser; uri <- uris) yield (uri, user, uniqueTitle)
    saveBookmarksByEdges(edges, isPrivate, source)
  }

  def getCollection(collectionId: Id[Collection]): Collection = {
    allCollections(collectionId)
  }

  def saveUserExperiment(experiment: UserExperiment): UserExperiment = {
    val id = experiment.id.getOrElse(nextUserExperimentId())
    val userId = experiment.userId
    val experimentWithId = experiment.withId(id)
    allUserExperiments(userId) = allUserExperiments.getOrElse(userId, Set.empty) + experimentWithId
    experimentWithId
  }

  def addEmails(emails: (Id[User], EmailAddress)*) = {
    emails.foreach {
      case (userId, emailAddress) =>
        allUserEmails(userId) = allUserEmails.getOrElse(userId, Set.empty) + emailAddress
    }
  }

  def saveFriendRequests(requests: (Id[User], Id[User])*) = {
    requests.map { request =>
      allUserFriendRequests(request._1) = allUserFriendRequests.getOrElse(request._1, Nil) :+ request._2
    }
  }

  def saveLibraries(libs: Library*): Seq[Library] = {
    libs.map { lib =>
      val id = lib.id.getOrElse(nextLibraryId)
      val toBeInserted = lib.withId(id).copy(seq = nextLibrarySeq()).withUpdateTime(currentDateTime)
      allLibraries(id) = toBeInserted
      toBeInserted
    }
  }

  def saveLibraryMemberships(libMems: LibraryMembership*) = {
    libMems.foreach { libMem =>
      val isNewMember = libMem.id.isEmpty
      val id = libMem.id.getOrElse(nextLibraryMembershipId)
      val library = allLibraries(libMem.libraryId)
      val updatedLibrary = if (isNewMember) library.copy(memberCount = library.memberCount + 1) else library
      saveLibraries(updatedLibrary)
      allLibraryMemberships(id) = libMem.withId(id).copy(seq = nextLibraryMembershipSeq())
    }
  }

  // ShoeboxServiceClient methods

  def getUserOpt(id: ExternalId[User]): Future[Option[User]] = {
    val userOpt = allUserExternalIds.get(id)
    Future.successful(userOpt)
  }

  def getUser(id: Id[User]): Future[Option[User]] = {
    val user = Option(allUsers(id))
    Future.successful(user)
  }

  def getNormalizedURI(uriId: Id[NormalizedURI]): Future[NormalizedURI] = {
    val uri = allNormalizedURIs(uriId)
    Future.successful(uri)
  }

  def getNormalizedURIs(ids: Seq[Id[NormalizedURI]]): Future[Seq[NormalizedURI]] = {
    val uris = ids.map(allNormalizedURIs(_))
    Future.successful(uris)
  }

  def getNormalizedURIByURL(url: String): Future[Option[NormalizedURI]] = Future.successful(allNormalizedURIs.values.find(_.url == url))

  def getNormalizedUriByUrlOrPrenormalize(url: String): Future[Either[NormalizedURI, String]] = ???

  def internNormalizedURI(url: URI, scrapeWanted: Boolean): Future[NormalizedURI] = {
    val uri = allNormalizedURIs.values.find(_.url == url).getOrElse {
      NormalizedURI(
        id = Some(Id[NormalizedURI](url.hashCode)),
        url = url.toString(),
        urlHash = UrlHash(url.hashCode.toString),
        screenshotUpdatedAt = None
      )
    }
    Future.successful(uri)
  }

  def getBookmarks(userId: Id[User]): Future[Seq[Keep]] = {
    val bookmarks = allUserBookmarks.getOrElse(userId, Set.empty).map(allBookmarks(_)).toSeq
    Future.successful(bookmarks)
  }

  def getBookmarksChanged(seqNum: SequenceNumber[Keep], fetchSize: Int): Future[Seq[Keep]] = {
    val bookmarks = allBookmarks.values.filter(_.seq > seqNum).toSeq.sortBy(_.seq).take(fetchSize)
    Future.successful(bookmarks)
  }

  def persistServerSearchEvent(metaData: JsObject): Unit = {
    //EventPersister.persist(Events.serverEvent(EventFamilies.SERVER_SEARCH, "search_return_hits", metaData.as[JsObject])(clock, fortyTwoServices))
  }

  def getUsers(userIds: Seq[Id[User]]): Future[Seq[User]] = {
    val users = userIds.map(allUsers(_))
    Future.successful(users)
  }

  def getUserIdsByExternalIds(extIds: Seq[ExternalId[User]]): Future[Seq[Id[User]]] = {
    val ids = extIds.map { id =>
      allUserExternalIds.get(id).getOrElse {
        throw new Exception(s"can't find id $id in allUserExternalIds: $allUserExternalIds")
      }.id.get
    }
    Future.successful(ids)
  }

  def getBasicUsers(userIds: Seq[Id[User]]): Future[Map[Id[User], BasicUser]] = {
    val basicUsers = userIds.map { id =>
      val dummyUser = User(
        id = Some(id),
        firstName = "Douglas",
        lastName = "Adams-clone-" + id.toString,
        username = Username("adams"),
        normalizedUsername = "adams"
      )
      val user = allUsers.getOrElse(id, dummyUser)
      id -> BasicUser.fromUser(user)
    }.toMap
    Future.successful(basicUsers)
  }

  def getBasicUsersNoCache(userIds: Seq[Id[User]]): Future[Map[Id[User], BasicUser]] = {
    val basicUsers = userIds.map { id =>
      val dummyUser = User(
        id = Some(id),
        firstName = "Douglas",
        lastName = "Adams-clone-" + id.toString,
        username = Username("adams"),
        normalizedUsername = "adams"
      )
      val user = allUsers.getOrElse(id, dummyUser)
      id -> BasicUser.fromUser(user)
    }.toMap
    Future.successful(basicUsers)
  }

  def getEmailAddressesForUsers(userIds: Seq[Id[User]]): Future[Map[Id[User], Seq[EmailAddress]]] = {
    val m = userIds.map { id => id -> allUserEmails.getOrElse(id, Set.empty).toSeq }.toMap
    Future.successful(m)
  }

  def getPrimaryEmailAddressForUsers(userIds: Seq[Id[User]]): Future[Map[Id[User], Option[EmailAddress]]] = {
    val m = allUserEmails collect {
      case (id, emails) if userIds.contains(id) => (id, emails.headOption)
    }
    Future.successful(m.toMap)
  }

  def sendMail(email: ElectronicMail): Future[Boolean] = synchronized {
    sentMail += email
    Future.successful(true)
  }

  def sendMailToUser(userId: Id[User], email: ElectronicMail): Future[Boolean] = ???
  def getPhrasesChanged(seqNum: SequenceNumber[Phrase], fetchSize: Int): Future[Seq[Phrase]] = Future.successful(Seq())
  def getSocialUserInfoByNetworkAndSocialId(id: SocialId, networkType: SocialNetworkType): Future[Option[SocialUserInfo]] = ???
  def getSocialUserInfosByUserId(userId: Id[User]): Future[List[SocialUserInfo]] = {
    Future.successful(socialUserInfosByUserId(userId))
  }
  def getSessionByExternalId(sessionId: UserSessionExternalId): Future[Option[UserSessionView]] = ???

  def getNormalizedUriUpdates(lowSeq: SequenceNumber[ChangedURI], highSeq: SequenceNumber[ChangedURI]): Future[Seq[(Id[NormalizedURI], NormalizedURI)]] = ???

  def getCollectionsChanged(seqNum: SequenceNumber[Collection], fetchSize: Int): Future[Seq[Collection]] = {
    val collections = allCollections.values.filter(_.seq > seqNum).toSeq.sortBy(_.seq).take(fetchSize)
    Future.successful(collections)
  }

  def getUriIdsInCollection(collectionId: Id[Collection]): Future[Seq[KeepUriAndTime]] = {
    val bookmarks = allCollectionBookmarks(collectionId).map(allBookmarks(_)).toSeq
    Future.successful(bookmarks map { b => KeepUriAndTime(b.uriId, b.createdAt) })
  }

  def getCollectionsByUser(userId: Id[User]): Future[Seq[Collection]] = {
    val collections = allCollections.values.filter(_.userId == userId).toSeq
    Future.successful(collections)
  }

  def getCollectionIdsByExternalIds(collIds: Seq[ExternalId[Collection]]): Future[Seq[Id[Collection]]] = ???

  def getIndexable(seqNum: SequenceNumber[NormalizedURI], fetchSize: Int = -1): Future[Seq[NormalizedURI]] = {
    val uris = allNormalizedURIs.values.filter(_.seq > seqNum).toSeq.sortBy(_.seq)
    val fewerUris = (if (fetchSize >= 0) uris.take(fetchSize) else uris)
    Future.successful(fewerUris)
  }

  def getIndexableUris(seqNum: SequenceNumber[NormalizedURI], fetchSize: Int = -1): Future[Seq[IndexableUri]] = {
    val uris = allNormalizedURIs.values.filter(_.seq > seqNum).toSeq.sortBy(_.seq)
    val fewerUris = (if (fetchSize >= 0) uris.take(fetchSize) else uris)
    Future.successful(fewerUris map { u => IndexableUri(u) })
  }

  def getScrapedUris(seqNum: SequenceNumber[NormalizedURI], fetchSize: Int = -1): Future[Seq[IndexableUri]] = {
    val scrapedStates = Set(NormalizedURIStates.SCRAPED, NormalizedURIStates.SCRAPE_FAILED, NormalizedURIStates.UNSCRAPABLE)
    val uris = allNormalizedURIs.values.filter(x => x.seq > seqNum && scrapedStates.contains(x.state)).toSeq.sortBy(_.seq)
    val fewerUris = (if (fetchSize >= 0) uris.take(fetchSize) else uris)
    Future.successful(fewerUris map { u => IndexableUri(u) })
  }

  def getHighestUriSeq(): Future[SequenceNumber[NormalizedURI]] = {
    val seq = allNormalizedURIs.values.map { _.seq }
    Future.successful(if (seq.isEmpty) SequenceNumber.ZERO else seq.max)
  }

  def getUserIndexable(seqNum: SequenceNumber[User], fetchSize: Int): Future[Seq[User]] = {
    val users = allUsers.values.filter(_.seq > seqNum).toSeq.sortBy(_.seq).take(fetchSize)
    Future.successful(users)
  }

  def getBookmarkByUriAndUser(uriId: Id[NormalizedURI], userId: Id[User]): Future[Option[Keep]] = {
    val bookmark = allUserBookmarks(userId).map(allBookmarks(_)).find(_.uriId == uriId)
    Future.successful(bookmark)
  }

  def getActiveExperiments: Future[Seq[SearchConfigExperiment]] = {
    val exp = allSearchExperiments.values.filter(_.isActive).toSeq
    Future.successful(exp)
  }

  def getExperiments: Future[Seq[SearchConfigExperiment]] = {
    val exp = allSearchExperiments.values.filter(_.state != SearchConfigExperimentStates.ACTIVE).toSeq
    Future.successful(exp)
  }

  def getExperiment(id: Id[SearchConfigExperiment]): Future[SearchConfigExperiment] = {
    val exp = allSearchExperiments(id)
    Future.successful(exp)
  }

  def saveExperiment(experiment: SearchConfigExperiment): Future[SearchConfigExperiment] = {
    val id = experiment.id.getOrElse(nextSearchExperimentId)
    val experimentWithId = experiment.withId(id)
    allSearchExperiments(experimentWithId.id.get) = experimentWithId

    val allActive = allSearchExperiments.values.filter(_.isActive).toSeq
    val generator = ProbabilisticExperimentGenerator(
      name = SearchConfigExperiment.probabilisticGenerator,
      condition = None,
      salt = SearchConfigExperiment.probabilisticGenerator.name,
      density = SearchConfigExperiment.getDensity(allActive)
    )
    allProbabilisticExperimentGenerators(generator.name) = generator
    Future.successful(experimentWithId)
  }

  def getUserExperiments(userId: Id[User]): Future[Seq[ExperimentType]] = {
    val states = allUserExperiments.getOrElse(userId, Set.empty).filter(_.state == UserExperimentStates.ACTIVE).map(_.experimentType).toSeq
    Future.successful(states)
  }

  def getExperimentsByUserIds(userIds: Seq[Id[User]]): Future[Map[Id[User], Set[ExperimentType]]] = {
    val exps = userIds.map { id =>
      val exps = allUserExperiments.getOrElse(id, Set.empty).filter(_.state == UserExperimentStates.ACTIVE).map(_.experimentType)
      id -> exps
    }.toMap
    Future.successful(exps)
  }

  def getExperimentGenerators(): Future[Seq[ProbabilisticExperimentGenerator]] = {
    Future.successful(allProbabilisticExperimentGenerators.values.filter(_.isActive).toSeq)
  }

  def getUsersByExperiment(experimentType: ExperimentType): Future[Set[User]] = {
    Future.successful(allUsers.map(_._2).toSet)
  }

  def getSearchFriends(userId: Id[User]): Future[Set[Id[User]]] = {
    Future.successful(allUserConnections.getOrElse(userId, Set.empty))
  }

  def getFriends(userId: Id[User]): Future[Set[Id[User]]] = {
    Future.successful(allUserConnections.getOrElse(userId, Set.empty))
  }

  def getUnfriends(userId: Id[User]): Future[Set[Id[User]]] = {
    val unfriends = allSearchFriends.values.filter(x => x.userId == userId && x.state == SearchFriendStates.EXCLUDED).map { _.friendId }
    Future.successful(unfriends.toSet)
  }

  def logEvent(userId: Id[User], event: JsObject) = {}

  def createDeepLink(initiator: Option[Id[User]], recipient: Id[User], uriId: Id[NormalizedURI], locator: DeepLocator): Unit = {}

  def getDeepUrl(locator: DeepLocator, recipient: Id[User]): Future[String] = ???

  def getHelpRankInfos(uriIds: Seq[Id[NormalizedURI]]): Future[Seq[HelpRankInfo]] = Future.successful {
    uriIds.map(HelpRankInfo(_, 0, 0))
  }

  def getFriendRequestsRecipientIdBySender(senderId: Id[User]): Future[Seq[Id[User]]] = {
    Future.successful(allUserFriendRequests.getOrElse(senderId, Seq()))
  }

  def getUserValue(userId: Id[User], key: UserValueName): Future[Option[String]] = Future.successful(allUserValues.get((userId, key)))

  def setUserValue(userId: Id[User], key: UserValueName, value: String): Unit = allUserValues((userId, key)) = value

  def getUserSegment(userId: Id[User]): Future[UserSegment] = Future.successful(UserSegment(Int.MaxValue))

  def getExtensionVersion(installationId: ExternalId[KifiInstallation]): Future[String] = Future.successful("dummy")

  def triggerRawKeepImport(): Unit = ()

  def triggerSocialGraphFetch(socialUserInfoId: Id[SocialUserInfo]): Future[Unit] = {
    Future.successful(())
  }

  def getUserConnectionsChanged(seq: SequenceNumber[UserConnection], fetchSize: Int): Future[Seq[UserConnection]] = {
    val changed = allConnections.values.filter(_.seq > seq).toSeq.sortBy(_.seq)
    Future.successful(if (fetchSize < 0) changed else changed.take(fetchSize))
  }

  def getSearchFriendsChanged(seq: SequenceNumber[SearchFriend], fetchSize: Int): Future[Seq[SearchFriend]] = {
    val changed = allSearchFriends.values.filter(_.seq > seq).toSeq.sortBy(_.seq)
    Future.successful(if (fetchSize < 0) changed else changed.take(fetchSize))
  }

  def updateURIRestriction(id: Id[NormalizedURI], r: Option[Restriction]): Future[Unit] = ???

  def getUriSummary(request: URISummaryRequest): Future[URISummary] = Future.successful(URISummary())

  def getUriSummaries(uriIds: Seq[Id[NormalizedURI]]): Future[Map[Id[NormalizedURI], URISummary]] = Future.successful {
    val uriSet = uriIds.toSet
    uriSummaries.toMap.filter { pair => uriSet.contains(pair._1) }
  }

  def getCandidateURIs(uris: Seq[Id[NormalizedURI]]): Future[Seq[Boolean]] = {
    callsGetToCandidateURIs += uris
    Future.successful(Seq.fill(uris.size)(true))
  }

  def getUserImageUrl(userId: Id[User], width: Int): Future[String] = synchronized {
    Future.successful(allUserImageUrls.getOrElse(userId, "https://www.kifi.com/assets/img/ghost.200.png"))
  }

  def getUnsubscribeUrlForEmail(email: EmailAddress): Future[String] = Future.successful("https://kifi.com")

  def getIndexableSocialConnections(seqNum: SequenceNumber[SocialConnection], fetchSize: Int): Future[Seq[IndexableSocialConnection]] = Future.successful(Seq.empty)

  def getIndexableSocialUserInfos(seqNum: SequenceNumber[SocialUserInfo], fetchSize: Int): Future[Seq[SocialUserInfo]] = Future.successful(Seq.empty)

  def getEmailAccountUpdates(seqNum: SequenceNumber[EmailAccountUpdate], fetchSize: Int): Future[Seq[EmailAccountUpdate]] = Future.successful(Seq.empty)

  def getKeepsAndTagsChanged(seqNum: SequenceNumber[Keep], fetchSize: Int): Future[Seq[KeepAndTags]] = {
    val changedKeeps = allBookmarks.values.filter(_.seq > seqNum).toSeq.sortBy(_.seq).take(fetchSize)
    val changedKeepIds = changedKeeps.map(_.id.get).toSet
    val flattenTags: (Id[Collection], Set[Id[Keep]]) => Set[(Id[Keep], Hashtag)] = {
      case (collectionId, keepIds) =>
        keepIds.collect { case keepId if changedKeepIds.contains(keepId) => (keepId, allCollections(collectionId).name) }
    }
    val tagsByChangedKeep = allCollectionBookmarks.toSet.flatMap(flattenTags.tupled).groupBy(_._1).mapValues(_.map(_._2)).withDefaultValue(Set.empty[Hashtag])
    val changedKeepsAndTags = changedKeeps.map { keep => KeepAndTags(keep, tagsByChangedKeep(keep.id.get)) }
    Future.successful(changedKeepsAndTags)
  }

  def getLapsedUsersForDelighted(maxCount: Int, skipCount: Int, after: DateTime, before: Option[DateTime]): Future[Seq[DelightedUserRegistrationInfo]] = Future.successful(Seq.empty)

  def getAllFakeUsers(): Future[Set[Id[User]]] = Future.successful(Set.empty)

  def getInvitations(senderId: Id[User]): Future[Seq[Invitation]] = Future.successful(Seq.empty)

  def getSocialConnections(userId: Id[User]): Future[Seq[SocialUserBasicInfo]] = Future.successful(Seq.empty)

  def addInteractions(usedId: Id[User], actions: Seq[(Either[Id[User], EmailAddress], String)]) = {}

  def processAndSendMail(emailToSend: EmailToSend) = synchronized {
    val mail = ElectronicMail(
      from = emailToSend.from,
      to = Seq(emailToSend.to match {
        case Left(userId) => EmailAddress(s"user$userId@gmail.com")
        case Right(addr) => addr
      }),
      cc = emailToSend.cc,
      subject = emailToSend.subject,
      htmlBody = LargeString(emailToSend.htmlTemplate.body),
      textBody = emailToSend.textTemplate.map(_.body),
      category = emailToSend.category,
      senderUserId = emailToSend.senderUserId
    )
    sentMail += mail
    Future.successful(true)
  }

  def getLibrariesChanged(seqNum: SequenceNumber[Library], fetchSize: Int): Future[Seq[LibraryView]] = {
    val changed = allLibraries.values.filter(_.seq > seqNum).toSeq.sortBy(_.seq).take(fetchSize).map(Library.toLibraryView)
    Future.successful(changed)
  }

  def getDetailedLibrariesChanged(seqNum: SequenceNumber[Library], fetchSize: Int): Future[Seq[DetailedLibraryView]] = {
    val changed = allLibraries.values.filter(_.seq > seqNum).toSeq.sortBy(_.seq).take(fetchSize).map(lib => Library.toDetailedLibraryView(lib))
    Future.successful(changed)
  }

  def getLibraryMembershipsChanged(seqNum: SequenceNumber[LibraryMembership], fetchSize: Int): Future[Seq[LibraryMembershipView]] = {
    val changed = allLibraryMemberships.values.filter(_.seq > seqNum).toSeq.sortBy(_.seq).take(fetchSize).map { _.toLibraryMembershipView }
    Future.successful(changed)
  }

  def canViewLibrary(libraryId: Id[Library], userId: Option[Id[User]], authToken: Option[String], hashedCode: Option[HashedPassPhrase]): Future[Boolean] = {
    Future.successful(true)
  }

  def newKeepsInLibraryForEmail(userId: Id[User], max: Int): Future[Seq[Keep]] =
    Future.successful(newKeepsInLibrariesExpectation(userId).take(max))

  def getBasicKeeps(userId: Id[User], uriIds: Set[Id[NormalizedURI]]): Future[Map[Id[NormalizedURI], Set[BasicKeep]]] = Future.successful {
    (allUserBookmarks(userId).map(allBookmarks(_)).groupBy(_.uriId) -- uriIds).mapValues(_.map { keep =>
      BasicKeep(
        keep.externalId,
        keep.userId == userId,
        true,
        keep.visibility,
        Library.publicId(keep.libraryId.get)
      )
    })
  }

  def getBasicLibraryStatistics(libraryIds: Set[Id[Library]]): Future[Map[Id[Library], BasicLibraryStatistics]] = ???

  def getKeepCounts(userId: Set[Id[User]]): Future[Map[Id[User], Int]] = ???

  def getLibraryImageUrls(libraryIds: Set[Id[Library]], idealImageSize: ImageSize): Future[Map[Id[Library], String]] = ???

  def getLibrariesWithWriteAccess(userId: Id[User]): Future[Set[Id[Library]]] = Future.successful {
    allLibraryMemberships.values.collect { case membership if membership.userId == userId && membership.canWrite => membership.libraryId }.toSet
  }

  def getUserActivePersonas(userId: Id[User]): Future[UserActivePersonas] = Future.successful(UserActivePersonas(Seq(), Seq()))

}
