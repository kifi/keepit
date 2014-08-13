package com.keepit.shoebox

import com.keepit.common.healthcheck.{ FakeAirbrakeNotifier, AirbrakeNotifier }
import com.keepit.common.service.ServiceType
import com.keepit.common.zookeeper.ServiceCluster
import com.keepit.model._
import com.keepit.common.db._
import collection.mutable
import scala.concurrent.Future
import com.keepit.search._
import java.util.concurrent.atomic.AtomicInteger
import collection.mutable.{ Map => MutableMap }
import com.keepit.social.{ SocialNetworkType, BasicUser }
import com.keepit.common.mail.{ EmailAddress, ElectronicMail }
import com.keepit.social.SocialId
import play.api.libs.json.JsObject
import com.keepit.scraper.{ ScrapeRequest, Signature, HttpRedirect }
import com.google.inject.util.Providers
import com.keepit.common.usersegment.UserSegment
import com.keepit.common.actor.FakeScheduler
import org.joda.time.DateTime
import com.keepit.eliza.model.ThreadItem
import com.kifi.franz.QueueName
import com.keepit.graph.manager._
import com.keepit.social.SocialId
import play.api.libs.json.JsObject
import com.keepit.heimdal.SanitizedKifiHit
import com.keepit.model.serialize.UriIdAndSeq

class FakeShoeboxScraperClientImpl(val airbrakeNotifier: AirbrakeNotifier) extends ShoeboxScraperClient {
  val serviceCluster: ServiceCluster = new ServiceCluster(ServiceType.TEST_MODE, Providers.of(airbrakeNotifier), new FakeScheduler(), () => {})

  protected def httpClient: com.keepit.common.net.HttpClient = ???

  def assignScrapeTasks(zkId: Long, max: Int): Future[Seq[ScrapeRequest]] = {
    Future.successful(Seq.empty[ScrapeRequest])
  }

  def getUriImage(nUriId: Id[NormalizedURI]): Future[Option[String]] = Future.successful(Some("http://www.adummyurl.com"))

  def getAllURLPatterns(): Future[Seq[UrlPatternRule]] = ???

  def saveScrapeInfo(info: ScrapeInfo): Future[Unit] = ???

  def savePageInfo(pageInfo: PageInfo): Future[Unit] = ???

  def getImageInfo(id: Id[ImageInfo]): Future[ImageInfo] = ???

  def updateScreenshots(nUriId: Id[NormalizedURI]): Future[Unit] = Future.successful(())

  def saveImageInfo(imageInfo: ImageInfo): Future[ImageInfo] = ???

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

  def scraped(uri: NormalizedURI, info: ScrapeInfo): Future[Option[NormalizedURI]] = ???

  def scrapeFailed(uri: NormalizedURI, info: ScrapeInfo): Future[Option[NormalizedURI]] = ???

  def recordPermanentRedirect(uri: NormalizedURI, redirect: HttpRedirect): Future[NormalizedURI] = ???

  def recordScrapedNormalization(uriId: Id[NormalizedURI], signature: Signature, candidateUrl: String, candidateNormalization: Normalization, alternateUrls: Set[String]): Future[Unit] = ???

  def getProxy(url: String): Future[Option[HttpProxy]] = ???

  def getProxyP(url: String): Future[Option[HttpProxy]] = ???

  def isUnscrapable(url: String, destinationUrl: Option[String]): Future[Boolean] = ???

  def isUnscrapableP(url: String, destinationUrl: Option[String]): Future[Boolean] = Future.successful(false)

  def getLatestKeep(url: String): Future[Option[Keep]] = ???

  def saveBookmark(bookmark: Keep): Future[Keep] = ???

  def getBookmarksByUriWithoutTitle(uriId: Id[NormalizedURI]): Future[Seq[Keep]] = ???
}
// code below should be sync with code in ShoeboxController
class FakeShoeboxServiceClientImpl(val airbrakeNotifier: AirbrakeNotifier) extends ShoeboxServiceClient {
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

  private val friendRequestIdCounter = new AtomicInteger(0)
  private def nextFriendRequestId = Id[FriendRequest](friendRequestIdCounter.incrementAndGet())

  private val userConnIdCounter = new AtomicInteger(0)
  private def nextUserConnId = Id[UserConnection](userConnIdCounter.incrementAndGet())

  private val searchFriendIdCounter = new AtomicInteger(0)
  private def nextSearchFriendId = Id[SearchFriend](searchFriendIdCounter.incrementAndGet())

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

  // Fake repos

  val allUsers = MutableMap[Id[User], User]()
  val allUserExternalIds = MutableMap[ExternalId[User], User]()
  val allUserConnections = MutableMap[Id[User], Set[Id[User]]]()
  val allConnections = MutableMap[Id[UserConnection], UserConnection]()
  val allSearchFriends = MutableMap[Id[SearchFriend], SearchFriend]()
  val allUserExperiments = MutableMap[Id[User], Set[UserExperiment]]()
  val allProbabilisticExperimentGenerators = MutableMap[Name[ProbabilisticExperimentGenerator], ProbabilisticExperimentGenerator]()
  val allUserBookmarks = MutableMap[Id[User], Set[Id[Keep]]]()
  val allBookmarks = MutableMap[Id[Keep], Keep]()
  val allNormalizedURIs = MutableMap[Id[NormalizedURI], NormalizedURI]()
  val uriToUrl = MutableMap[Id[NormalizedURI], URL]()
  val allCollections = MutableMap[Id[Collection], Collection]()
  val allCollectionBookmarks = MutableMap[Id[Collection], Set[Id[Keep]]]()
  val allSearchExperiments = MutableMap[Id[SearchConfigExperiment], SearchConfigExperiment]()
  val allUserEmails = MutableMap[Id[User], Set[EmailAddress]]()
  val allUserValues = MutableMap[(Id[User], UserValueName), String]()
  val allFriendRequests = MutableMap[Id[FriendRequest], FriendRequest]()
  val allUserFriendRequests = MutableMap[Id[User], Seq[FriendRequest]]()
  val sentMail = mutable.MutableList[ElectronicMail]()
  val uriSummaries = MutableMap[Id[NormalizedURI], URISummary]()

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

  def saveBookmarksByEdges(edges: Seq[(NormalizedURI, User, Option[String])], isPrivate: Boolean = false, source: KeepSource = KeepSource("fake")): Seq[Keep] = {
    val bookmarks = edges.map {
      case (uri, user, optionalTitle) => {
        val url = uriToUrl(uri.id.get)
        KeepFactory(url.url, uri = uri, userId = user.id.get, title = optionalTitle orElse uri.title, url = url, source = source, isPrivate = isPrivate, libraryId = None) // todo(andrew): Library id?
      }
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

  def saveFriendRequests(requests: FriendRequest*) = {
    requests.map { request =>
      val id = request.id.getOrElse(nextFriendRequestId)
      val requestWithId = request.copy(id = Some(id))
      allFriendRequests(id) = requestWithId
      allUserFriendRequests(requestWithId.senderId) = allUserFriendRequests.getOrElse(requestWithId.senderId, Nil) :+ requestWithId
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

  def internNormalizedURI(url: String, scrapeWanted: Boolean): Future[NormalizedURI] = {
    val uri = allNormalizedURIs.values.find(_.url == url).getOrElse {
      NormalizedURI(
        id = Some(Id[NormalizedURI](url.hashCode)),
        url = url,
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
        username = None
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
        username = None
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

  def sendMail(email: ElectronicMail): Future[Boolean] = synchronized {
    sentMail += email
    Future.successful(true)
  }

  def sendMailToUser(userId: Id[User], email: ElectronicMail): Future[Boolean] = ???
  def getPhrasesChanged(seqNum: SequenceNumber[Phrase], fetchSize: Int): Future[Seq[Phrase]] = Future.successful(Seq())
  def getSocialUserInfoByNetworkAndSocialId(id: SocialId, networkType: SocialNetworkType): Future[Option[SocialUserInfo]] = ???
  def getSessionByExternalId(sessionId: com.keepit.common.db.ExternalId[com.keepit.model.UserSession]): scala.concurrent.Future[Option[com.keepit.model.UserSession]] = ???
  def getSocialUserInfosByUserId(userId: com.keepit.common.db.Id[com.keepit.model.User]): scala.concurrent.Future[List[com.keepit.model.SocialUserInfo]] = ???

  def getNormalizedUriUpdates(lowSeq: SequenceNumber[ChangedURI], highSeq: SequenceNumber[ChangedURI]): Future[Seq[(Id[NormalizedURI], NormalizedURI)]] = ???

  def getCollectionsChanged(seqNum: SequenceNumber[Collection], fetchSize: Int): Future[Seq[Collection]] = {
    val collections = allCollections.values.filter(_.seq > seqNum).toSeq.sortBy(_.seq).take(fetchSize)
    Future.successful(collections)
  }

  def getBookmarksInCollection(collectionId: Id[Collection]): Future[Seq[Keep]] = {
    val bookmarks = allCollectionBookmarks(collectionId).map(allBookmarks(_)).toSeq
    Future.successful(bookmarks)
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

  def getFriendRequestsBySender(senderId: Id[User]): Future[Seq[FriendRequest]] = {
    Future.successful(allUserFriendRequests.getOrElse(senderId, Seq()))
  }

  def getUserValue(userId: Id[User], key: UserValueName): Future[Option[String]] = Future.successful(allUserValues.get((userId, key)))

  def setUserValue(userId: Id[User], key: UserValueName, value: String): Unit = allUserValues((userId, key)) = value

  def getUserSegment(userId: Id[User]): Future[UserSegment] = Future.successful(UserSegment(Int.MaxValue))

  def getExtensionVersion(installationId: ExternalId[KifiInstallation]): Future[String] = Future.successful("dummy")

  def triggerRawKeepImport(): Unit = ()

  def triggerSocialGraphFetch(socialUserInfoId: Id[SocialUserInfo]): Future[Unit] = {
    Future.successful()
  }

  def getUserConnectionsChanged(seq: SequenceNumber[UserConnection], fetchSize: Int): Future[Seq[UserConnection]] = {
    val changed = allConnections.values.filter(_.seq > seq).toSeq.sortBy(_.seq)
    Future.successful(if (fetchSize < 0) changed else changed.take(fetchSize))
  }

  def getSearchFriendsChanged(seq: SequenceNumber[SearchFriend], fetchSize: Int): Future[Seq[SearchFriend]] = {
    val changed = allSearchFriends.values.filter(_.seq > seq).toSeq.sortBy(_.seq)
    Future.successful(if (fetchSize < 0) changed else changed.take(fetchSize))
  }

  def isSensitiveURI(uri: String): Future[Boolean] = {
    Future.successful(uri.contains("isSensitive"))
  }
  def updateURIRestriction(id: Id[NormalizedURI], r: Option[Restriction]): Future[Unit] = ???

  def getVerifiedAddressOwners(emailAddresses: Seq[EmailAddress]): Future[Map[EmailAddress, Id[User]]] = Future.successful(Map.empty)

  def sendUnreadMessages(threadItems: Seq[ThreadItem], otherParticipants: Set[Id[User]], userId: Id[User], title: String, deepLocator: DeepLocator, notificationUpdatedAt: DateTime): Future[Unit] = Future.successful(Unit)

  def getUriSummary(request: URISummaryRequest): Future[URISummary] = Future.successful(URISummary())

  def getUriSummaries(uriIds: Seq[Id[NormalizedURI]]): Future[Map[Id[NormalizedURI], URISummary]] = Future.successful {
    val uriSet = uriIds.toSet
    uriSummaries.toMap.filter { pair => uriSet.contains(pair._1) }
  }

  def getCandidateURIs(uris: Seq[Id[NormalizedURI]]): Future[Seq[Boolean]] = Future.successful(Seq.fill(uris.size)(false))

  def getUserImageUrl(userId: Id[User], width: Int): Future[String] = Future.successful("https://www.kifi.com/assets/img/ghost.200.png")

  def getUnsubscribeUrlForEmail(email: EmailAddress): Future[String] = Future.successful("https://kifi.com")

  def getIndexableSocialConnections(seqNum: SequenceNumber[SocialConnection], fetchSize: Int): Future[Seq[IndexableSocialConnection]] = Future.successful(Seq.empty)

  def getIndexableSocialUserInfos(seqNum: SequenceNumber[SocialUserInfo], fetchSize: Int): Future[Seq[SocialUserInfo]] = Future.successful(Seq.empty)

  def getEmailAccountUpdates(seqNum: SequenceNumber[EmailAccountUpdate], fetchSize: Int): Future[Seq[EmailAccountUpdate]] = Future.successful(Seq.empty)

  def getLibrariesAndMembershipsChanged(seqNum: SequenceNumber[Library], fetchSize: Int): Future[Seq[LibraryAndMemberships]] = Future.successful(Seq.empty)

  def getLapsedUsersForDelighted(maxCount: Int, skipCount: Int, after: DateTime, before: Option[DateTime]): Future[Seq[DelightedUserRegistrationInfo]] = Future.successful(Seq.empty)

  def getAllFakeUsers(): Future[Set[Id[User]]] = Future.successful(Set.empty)

  def getInvitations(senderId: Id[User]): Future[Seq[Invitation]] = Future.successful(Seq.empty)

  def getSocialConnections(userId: Id[User]): Future[Seq[SocialUserBasicInfo]] = Future.successful(Seq.empty)
}
