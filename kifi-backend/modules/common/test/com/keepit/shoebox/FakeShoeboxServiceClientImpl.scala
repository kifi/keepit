package com.keepit.shoebox

import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.service.ServiceType
import com.keepit.common.zookeeper.ServiceCluster
import com.keepit.common.logging.Logging
import com.keepit.model._
import com.keepit.common.db._
import scala.concurrent.Future
import com.keepit.search._
import com.keepit.model.Phrase
import com.keepit.model.NormalizedURI
import com.keepit.model.User
import java.util.concurrent.atomic.AtomicInteger
import collection.mutable.{Map => MutableMap}
import com.keepit.social.{SocialNetworkType, BasicUser}
import com.keepit.common.mail.{ElectronicMail}
import com.keepit.model.ExperimentType
import com.keepit.model.URL
import com.keepit.model.CommentRecipient
import com.keepit.model.UserExperiment
import com.keepit.social.SocialId
import com.keepit.model.UrlHash
import play.api.libs.json.JsObject
import com.keepit.scraper.HttpRedirect

// code below should be sync with code in ShoeboxController
class FakeShoeboxServiceClientImpl(val airbrakeNotifier: AirbrakeNotifier) extends ShoeboxServiceClient {
  val serviceCluster: ServiceCluster = new ServiceCluster(ServiceType.TEST_MODE)
  protected def httpClient: com.keepit.common.net.HttpClient = ???

  // Fake ID counters

  private val userIdCounter = new AtomicInteger(0)
  private def nextUserId() = { Id[User](userIdCounter.incrementAndGet()) }

  private val bookmarkIdCounter = new AtomicInteger(0)
  private def nextBookmarkId() = { Id[Bookmark](bookmarkIdCounter.incrementAndGet()) }

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

  private val commentIdCounter = new AtomicInteger(0)
  private def nextCommentId() = { Id[Comment](commentIdCounter.incrementAndGet()) }

  private val emailIdCounter = new AtomicInteger(0)
  private def nextEmailId = Id[EmailAddress](emailIdCounter.incrementAndGet())

  // Fake sequence counters

  private val userSeqCounter = new AtomicInteger(0)
  private def nextUserSeqNum() = SequenceNumber(userSeqCounter.incrementAndGet())

  private val uriSeqCounter = new AtomicInteger(0)
  private def nextUriSeqNum() = { SequenceNumber(uriSeqCounter.incrementAndGet()) }

  private val bookmarkSeqCounter = new AtomicInteger(0)
  private def nextBookmarkSeqNum() = { SequenceNumber(bookmarkSeqCounter.incrementAndGet()) }

  private val collectionSeqCounter = new AtomicInteger(0)
  private def nextCollectionSeqNum() = { SequenceNumber(collectionSeqCounter.incrementAndGet()) }

  private val commentSeqCounter = new AtomicInteger(0)
  private def nextCommentSeqNum() = { SequenceNumber(commentSeqCounter.incrementAndGet()) }

  // Fake repos

  val allUsers = MutableMap[Id[User], User]()
  val allUserExternalIds = MutableMap[ExternalId[User], User]()
  val allUserConnections = MutableMap[Id[User], Set[Id[User]]]()
  val allUserExperiments = MutableMap[Id[User], Set[UserExperiment]]()
  val allUserBookmarks = MutableMap[Id[User], Set[Id[Bookmark]]]()
  val allBookmarks = MutableMap[Id[Bookmark], Bookmark]()
  val allNormalizedURIs = MutableMap[Id[NormalizedURI], NormalizedURI]()
  val uriToUrl = MutableMap[Id[NormalizedURI], URL]()
  val allCollections = MutableMap[Id[Collection], Collection]()
  val allCollectionBookmarks = MutableMap[Id[Collection], Set[Id[Bookmark]]]()
  val allSearchExperiments = MutableMap[Id[SearchConfigExperiment], SearchConfigExperiment]()
  val allComments = MutableMap[Id[Comment], Comment]()
  val allCommentRecipients = MutableMap[Id[Comment], Set[CommentRecipient]]()
  val allEmails = MutableMap[Id[EmailAddress], EmailAddress]()
  val allUserEmails = MutableMap[Id[User], Seq[EmailAddress]]()

  // Fake data initialization methods

  def saveUsers(users: User*): Seq[User] = {
    users.map {user =>
      val id = user.id.getOrElse(nextUserId())
      val updatedUser = user.withId(id).copy(seq = nextUserSeqNum)
      allUsers(id) = updatedUser
      allUserExternalIds(updatedUser.externalId) = updatedUser
      updatedUser
    }
  }

  def saveURIs(uris: NormalizedURI*): Seq[NormalizedURI] = {
    uris.map {uri =>
      val id = uri.id.getOrElse(nextUriId())
      val updatedUri = uri.withId(id).copy(seq = nextUriSeqNum())
      val updatedUrl = uriToUrl.getOrElse(id, URLFactory(url = updatedUri.url, normalizedUriId = updatedUri.id.get).withId(nextUrlId()))
      allNormalizedURIs(id) = updatedUri
      uriToUrl(id) = updatedUrl
      updatedUri
    }
  }

  def saveConnections(connections: Map[Id[User], Set[Id[User]]]) {
    connections.foreach { case (userId, friends) =>
      allUserConnections(userId) = allUserConnections.getOrElse(userId, Set.empty) ++ friends
    }
  }

  def deleteConnections(connections: Map[Id[User], Set[Id[User]]]) {
    connections.foreach { case (userId, friends) =>
      allUserConnections(userId) = allUserConnections.getOrElse(userId, Set.empty) -- friends
    }
  }

  def clearUserConnections(userIds: Id[User]*) {
    userIds.map(allUserConnections(_) = Set.empty[Id[User]])
  }

  def saveBookmarks(bookmarks: Bookmark*): Seq[Bookmark] = {
    bookmarks.map {b =>
      val id = b.id.getOrElse(nextBookmarkId())
      val updatedBookmark = b.withId(id).copy(seq = nextBookmarkSeqNum())
      allBookmarks(id) = updatedBookmark
      allUserBookmarks(b.userId) = allUserBookmarks.getOrElse(b.userId, Set.empty) + id
      updatedBookmark
    }
  }

  def saveCollections(collections: Collection*): Seq[Collection] = {
    collections.map {c =>
      val id = c.id.getOrElse(nextCollectionId())
      val updatedCollection = c.withId(id).copy(seq = nextCollectionSeqNum())
      allCollections(id) = updatedCollection
      updatedCollection
    }
  }

  def saveBookmarksToCollection(collectionId: Id[Collection], bookmarks: Bookmark*) {
    allCollectionBookmarks(collectionId) = allCollectionBookmarks.getOrElse(collectionId, Set.empty) ++ bookmarks.map(_.id.get)
    allCollections(collectionId) = allCollections(collectionId).copy(seq = nextCollectionSeqNum())
  }

  def saveBookmarksByEdges(edges: Seq[(NormalizedURI, User, Option[String])], isPrivate: Boolean = false, source: BookmarkSource = BookmarkSource("fake")): Seq[Bookmark] = {
    val bookmarks = edges.map { case (uri, user, optionalTitle) => {
      val url = uriToUrl(uri.id.get)
      BookmarkFactory(uri = uri, userId = user.id.get, title = optionalTitle orElse uri.title, url = url, source = source, isPrivate = isPrivate)
    }}
    saveBookmarks(bookmarks:_*)
  }

  def saveBookmarksByURI(edgesByURI: Seq[(NormalizedURI, Seq[User])], uniqueTitle: Option[String] = None, isPrivate: Boolean = false, source: BookmarkSource = BookmarkSource("fake")): Seq[Bookmark] = {
    val edges = for ((uri, users) <- edgesByURI; user <- users) yield (uri, user, uniqueTitle)
    saveBookmarksByEdges(edges, isPrivate, source)
  }

  def saveBookmarksByUser(edgesByUser: Seq[(User, Seq[NormalizedURI])], uniqueTitle: Option[String] = None, isPrivate: Boolean = false, source: BookmarkSource = BookmarkSource("fake")): Seq[Bookmark] = {
    val edges = for ((user, uris) <- edgesByUser; uri <- uris) yield (uri, user, uniqueTitle)
    saveBookmarksByEdges(edges, isPrivate, source)
  }

  def getBookmarksByUriWithoutTitle(uriId: Id[NormalizedURI]): Future[Seq[Bookmark]] = ???

  def getLatestBookmark(uriId: Id[NormalizedURI]): Future[Option[Bookmark]] = ???

  def saveBookmark(bookmark: Bookmark): Future[Bookmark] = ???

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

  def saveComment(comment: Comment, recipientIds: Id[User]*): Comment = {
    val id = comment.id.getOrElse(nextCommentId())
    val updatedComment = comment.withId(id).copy(seq = nextCommentSeqNum())
    val commentRecipients = recipientIds match {
      case Nil => updatedComment.parent.map(allCommentRecipients(_)).getOrElse(Set.empty)
      case _ => recipientIds.map(userId => CommentRecipient(commentId = updatedComment.id.get, userId = Some(userId))).toSet
    }
    allComments(id) = updatedComment
    allCommentRecipients(id) = commentRecipients
    updatedComment
  }

  def saveEmails(emails: EmailAddress*) = {
    emails.map{ email =>
      val id = email.id.getOrElse(nextEmailId)
      val emailWithId = email.copy(id = Some(id))
      allEmails(id) = emailWithId
      allUserEmails(emailWithId.userId) = allUserEmails.getOrElse(emailWithId.userId, Nil) :+ emailWithId
    }
  }

  // ShoeboxServiceClient methods

  def getUserOpt(id: ExternalId[User]): Future[Option[User]] = {
    val userOpt =  allUserExternalIds.get(id)
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


  def internNormalizedURI(urls: JsObject): Future[NormalizedURI] = {
    val url = (urls \ "url").as[String]
    val uri = allNormalizedURIs.values.find(_.url == url).getOrElse {
      NormalizedURI(
        id = Some(Id[NormalizedURI](url.hashCode)),
        url=url,
        urlHash=UrlHash(url.hashCode.toString),
        screenshotUpdatedAt=None
      )
    }

    Future.successful(uri)
  }

  def getBookmarks(userId: Id[User]): Future[Seq[Bookmark]] = {
    val bookmarks = allUserBookmarks.getOrElse(userId, Set.empty).map(allBookmarks(_)).toSeq
    Future.successful(bookmarks)
  }

  def getBookmarksChanged(seqNum: SequenceNumber, fetchSize: Int): Future[Seq[Bookmark]] = {
    val bookmarks = allBookmarks.values.filter(_.seq > seqNum).toSeq.sortBy(_.seq).take(fetchSize)
    Future.successful(bookmarks)
  }

  def getCommentsChanged(seqNum: SequenceNumber, fetchSize: Int): Future[Seq[Comment]] = {
    val comments = allComments.values.filter(_.seq > seqNum).toSeq.sortBy(_.seq).take(fetchSize)
    Future.successful(comments)
  }

  def getCommentRecipientIds(commentId: Id[Comment]): Future[Seq[Id[User]]] = {
    val commentRecipientIds = allCommentRecipients.getOrElse(commentId, Set.empty).filter(_.state == CommentRecipientStates.ACTIVE).map(_.userId.get).toSeq
    Future.successful(commentRecipientIds)
  }


  def persistServerSearchEvent(metaData: JsObject): Unit ={
    //EventPersister.persist(Events.serverEvent(EventFamilies.SERVER_SEARCH, "search_return_hits", metaData.as[JsObject])(clock, fortyTwoServices))
  }

  def getUsers(userIds: Seq[Id[User]]): Future[Seq[User]] = {
    val users = userIds.map(allUsers(_))
    Future.successful(users)
  }

  def getUserIdsByExternalIds(extIds: Seq[ExternalId[User]]): Future[Seq[Id[User]]] = {
    val ids = extIds.map(allUserExternalIds(_).id.get)
    Future.successful(ids)
  }

  def getBasicUsers(userIds: Seq[Id[User]]): Future[Map[Id[User], BasicUser]] = {
    val basicUsers = userIds.map { id =>
      val dummyUser = User(
        id = Some(id),
        firstName = "Douglas",
        lastName = "Adams-clone-" + id.toString
      )
      val user = allUsers.getOrElse(id,dummyUser)
      id -> BasicUser(
        externalId = user.externalId,
        firstName = user.firstName,
        lastName = user.lastName,
        pictureName = "fake.jpg" //
      )
    }.toMap
    Future.successful(basicUsers)
  }

  def getEmailAddressesForUsers(userIds: Seq[Id[User]]): Future[Map[Id[User], Seq[String]]] = {
    val m = userIds.map{ id => id -> allUserEmails.getOrElse(id, Nil).map{_.address}}.toMap
    Future.successful(m)
  }


  def sendMail(email: com.keepit.common.mail.ElectronicMail): Future[Boolean] = ???
  def sendMailToUser(userId: Id[User], email: ElectronicMail): Future[Boolean] = ???
  def getPhrasesByPage(page: Int, size: Int): Future[Seq[Phrase]] = Future.successful(Seq())
  def getSocialUserInfoByNetworkAndSocialId(id: SocialId, networkType: SocialNetworkType): Future[Option[SocialUserInfo]] = ???
  def getSessionByExternalId(sessionId: com.keepit.common.db.ExternalId[com.keepit.model.UserSession]): scala.concurrent.Future[Option[com.keepit.model.UserSession]] = ???
  def getSocialUserInfosByUserId(userId: com.keepit.common.db.Id[com.keepit.model.User]): scala.concurrent.Future[List[com.keepit.model.SocialUserInfo]] = ???

  def uriChannelFanout(uri: String,msg: play.api.libs.json.JsArray): Seq[scala.concurrent.Future[Int]] = Seq()
  def userChannelFanout(userId: com.keepit.common.db.Id[com.keepit.model.User],msg: play.api.libs.json.JsArray): Seq[scala.concurrent.Future[Int]] = Seq()
  def uriChannelCountFanout(): Seq[scala.concurrent.Future[Int]] = Seq()
  def userChannelBroadcastFanout(msg: play.api.libs.json.JsArray): Seq[scala.concurrent.Future[Int]] = Seq()
  def userChannelCountFanout(): Seq[scala.concurrent.Future[Int]] = Seq()

  def suggestExperts(urisAndKeepers: Seq[(Id[NormalizedURI], Seq[Id[User]])]): Future[Seq[Id[User]]] = ???
  def getNormalizedUriUpdates(lowSeq: Long, highSeq: Long): Future[Seq[(Id[NormalizedURI], NormalizedURI)]] = ???

  def getCollectionsChanged(seqNum: SequenceNumber, fetchSize: Int): Future[Seq[Collection]] = {
    val collections = allCollections.values.filter(_.seq > seqNum).toSeq.sortBy(_.seq).take(fetchSize)
    Future.successful(collections)
  }

  def getBookmarksInCollection(collectionId: Id[Collection]): Future[Seq[Bookmark]] = {
    val bookmarks = allCollectionBookmarks(collectionId).map(allBookmarks(_)).toSeq
    Future.successful(bookmarks)
  }

  def getCollectionsByUser(userId: Id[User]): Future[Seq[Collection]] = {
    val collections = allCollections.values.filter(_.userId == userId).toSeq
    Future.successful(collections)
  }

  def getCollectionIdsByExternalIds(collIds: Seq[ExternalId[Collection]]): Future[Seq[Id[Collection]]] = ???

  def getIndexable(seqNum: Long, fetchSize: Int = -1) : Future[Seq[NormalizedURI]] = {
    val uris = allNormalizedURIs.values.filter(_.seq > SequenceNumber(seqNum)).toSeq.sortBy(_.seq)
    val fewerUris = (if (fetchSize >= 0) uris.take(fetchSize) else uris)
    Future.successful(fewerUris)
  }

  def getUserIndexable(seqNum: Long, fetchSize: Int): Future[Seq[User]] = {
    val users = allUsers.values.filter(_.seq.value > seqNum).toSeq.sortBy(_.seq.value).take(fetchSize)
    Future.successful(users)
  }

  def getBookmarkByUriAndUser(uriId: Id[NormalizedURI], userId: Id[User]): Future[Option[Bookmark]] = {
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
    allSearchExperiments(experimentWithId.id.get) =  experimentWithId
    Future.successful(experimentWithId)
  }

  def getUserExperiments(userId: Id[User]): Future[Seq[ExperimentType]] = {
    val states = allUserExperiments.getOrElse(userId, Set.empty).filter(_.state == UserExperimentStates.ACTIVE).map(_.experimentType).toSeq
    Future.successful(states)
  }

  def getSearchFriends(userId: Id[User]): Future[Set[Id[User]]] = {
    Future.successful(allUserConnections.getOrElse(userId, Set.empty))
  }

  def getFriends(userId: Id[User]): Future[Set[Id[User]]] = {
    Future.successful(allUserConnections.getOrElse(userId, Set.empty))
  }

  def logEvent(userId: Id[User], event: JsObject) = {}

  def createDeepLink(initiator: Id[User], recipient: Id[User], uriId: Id[NormalizedURI], locator: DeepLocator) : Unit = {}

  def clickAttribution(clicker: Id[User], uriId: Id[NormalizedURI], keepers: ExternalId[User]*): Unit = {}

  def getScrapeInfo(uri: NormalizedURI): Future[ScrapeInfo] = ???

  def saveScrapeInfo(info: ScrapeInfo): Future[ScrapeInfo] = ???

  def saveNormalizedURI(uri: NormalizedURI): Future[NormalizedURI] = ???

  def recordPermanentRedirect(uri: NormalizedURI, redirect: HttpRedirect): Future[NormalizedURI] = ???

  def getProxy(url: String): Future[Option[HttpProxy]] = ???

  def getProxyP(url: String): Future[Option[HttpProxy]] = ???

  def isUnscrapable(url: String, destinationUrl: Option[String]): Future[Boolean] = ???

  def isUnscrapableP(url: String, destinationUrl: Option[String]): Future[Boolean] = ???
}
