package com.keepit.shoebox

import com.keepit.common.logging.Logging
import com.keepit.model._
import com.keepit.common.db._
import com.keepit.model.ClickHistory
import scala.concurrent.{Future, Promise}
import com.keepit.search._
import com.keepit.common.social.BasicUser
import com.keepit.common.social.SocialNetworkType
import com.keepit.model.ExperimentType
import com.keepit.model.Phrase
import com.keepit.model.NormalizedURI
import com.keepit.model.User
import com.keepit.model.UserExperiment
import com.keepit.search.ArticleSearchResult
import play.api.libs.json.JsObject
import com.keepit.common.social.SocialId
import java.util.concurrent.atomic.AtomicInteger

// code below should be sync with code in ShoeboxController
class FakeShoeboxServiceClientImpl(clickHistoryTracker: ClickHistoryTracker, browsingHistoryTracker: BrowsingHistoryTracker) extends ShoeboxServiceClient {
  val host: String = ""
  protected def httpClient: com.keepit.common.net.HttpClient = ???

  // Fake ID counters

  private val userIdCounter = new AtomicInteger(0)
  private def nextUserId = { Id[User](userIdCounter.incrementAndGet()) }

  private val bookmarkIdCounter = new AtomicInteger(0)
  private def nextBookmarkId = { Id[Bookmark](bookmarkIdCounter.incrementAndGet()) }

  private val uriIdCounter = new AtomicInteger(0)
  private def nextUriId = { Id[NormalizedURI](uriIdCounter.incrementAndGet()) }

  private val urlIdCounter = new AtomicInteger(0)
  private def nextUrlId = { Id[URL](urlIdCounter.incrementAndGet()) }

  private val collectionIdCounter = new AtomicInteger(0)
  private def nextCollectionId = { Id[Collection](collectionIdCounter.incrementAndGet()) }

  private val searchExpIdCounter = new AtomicInteger(0)
  private def nextSearchExperimentId = { Id[SearchConfigExperiment](searchExpIdCounter.incrementAndGet()) }

  private val userExpIdCounter = new AtomicInteger(0)
  private def nextUserExperimentId = { Id[UserExperiment](userExpIdCounter.incrementAndGet()) }

  private val commentIdCounter = new AtomicInteger(0)
  private def nextCommentId = { Id[Comment](commentIdCounter.incrementAndGet()) }

  // Fake sequence counters

  private val uriSeqCounter = new AtomicInteger(0)
  private def nextUriSeqNum = { SequenceNumber(uriSeqCounter.incrementAndGet()) }

  private val bookmarkSeqCounter = new AtomicInteger(0)
  private def nextBookmarkSeqNum = { SequenceNumber(bookmarkSeqCounter.incrementAndGet()) }

  private val collectionSeqCounter = new AtomicInteger(0)
  private def nextCollectionSeqNum = { SequenceNumber(collectionSeqCounter.incrementAndGet()) }

  private val commentSeqCounter = new AtomicInteger(0)
  private def nextCommentSeqNum = { SequenceNumber(commentSeqCounter.incrementAndGet()) }

  // Fake repos

  var allUsers = Map[Id[User], User]()
  var allUserExternalIds = Map[ExternalId[User], User]()
  var allUserConnections = Map[Id[User], Set[Id[User]]]()
  var allSocialUserInfos = Map[Id[User], Set[SocialUserInfo]]()
  var allUserExperiments = Map[Id[User], Set[UserExperiment]]()
  var allUserBookmarks = Map[Id[User], Set[Id[Bookmark]]]()
  var allBookmarks = Map[Id[Bookmark], Bookmark]()
  var allNormalizedURIs = Map[Id[NormalizedURI], NormalizedURI]()
  var uriToUrl = Map[Id[NormalizedURI], URL]()
  var allCollections = Map[Id[Collection], Collection]()
  var allCollectionBookmarks = Map[Id[Collection], Set[Id[Bookmark]]]()
  var allSearchExperiments = Map[Id[SearchConfigExperiment], SearchConfigExperiment]()
  var allComments = Map[Id[Comment], Comment]()
  var allCommentRecipients = Map[Id[Comment], Set[CommentRecipient]]()

  // Fake data initialization methods

  def saveUsers(users: User*): Seq[User] = {
    users.map {user =>
      val id = user.id.getOrElse(nextUserId)
      val updatedUser = user.withId(id)
      allUsers += (id -> updatedUser)
      allUserExternalIds += (updatedUser.externalId -> updatedUser)
      allSocialUserInfos += (id -> allSocialUserInfos.getOrElse(id, Set.empty))
      updatedUser
    }
  }

  def saveURIs(uris: NormalizedURI*): Seq[NormalizedURI] = {
    uris.map {uri =>
      val id = uri.id.getOrElse(nextUriId)
      val updatedUri = uri.withId(id).copy(seq = nextUriSeqNum)
      val updatedUrl = uriToUrl.getOrElse(id, URLFactory(url = updatedUri.url, normalizedUriId = updatedUri.id.get).withId(nextUrlId))
      allNormalizedURIs += (id -> updatedUri)
      uriToUrl += (id -> updatedUrl)
      updatedUri
    }
  }

  def saveConnections(connections: Map[Id[User], Set[Id[User]]]) {
    connections.foreach { case (userId, friends) =>
      allUserConnections += userId -> (allUserConnections.getOrElse(userId, Set.empty) ++ friends)
    }
  }

  def deleteConnections(connections: Map[Id[User], Set[Id[User]]]) {
    connections.foreach { case (userId, friends) =>
      allUserConnections += userId -> (allUserConnections.getOrElse(userId, Set.empty) -- friends)
    }
  }

  def clearUserConnections(userIds: Id[User]*) {
    allUserConnections ++= userIds.map(_ -> Set.empty[Id[User]])
  }

  def saveBookmarks(bookmarks: Bookmark*): Seq[Bookmark] = {
    bookmarks.map {b =>
      val id = b.id.getOrElse(nextBookmarkId)
      val updatedBookmark = b.withId(id).copy(seq = nextBookmarkSeqNum)
      allBookmarks += (id -> updatedBookmark)
      allUserBookmarks += b.userId -> (allUserBookmarks.getOrElse(b.userId, Set.empty) + id)
      updatedBookmark
    }
  }

  def saveCollections(collections: Collection*): Seq[Collection] = {
    collections.map {c =>
      val id = c.id.getOrElse(nextCollectionId)
      val updatedCollection = c.withId(id).copy(seq = nextCollectionSeqNum)
      allCollections += (id -> updatedCollection)
      updatedCollection
    }
  }

  def saveBookmarksToCollection(collectionId: Id[Collection], bookmarks: Bookmark*) {
    allCollectionBookmarks += collectionId -> (allCollectionBookmarks.getOrElse(collectionId, Set.empty) ++ bookmarks.map(_.id.get))
    allCollections += (collectionId -> allCollections(collectionId).copy(seq = nextCollectionSeqNum))
  }

  def saveBookmarksByEdges(edges: Seq[(NormalizedURI, User, Option[String])], isPrivate: Boolean = false, source: BookmarkSource = BookmarkSource("fake")): Seq[Bookmark] = {
    val bookmarks = edges.map { case (uri, user, optionalTitle) => {
      val url = uriToUrl(uri.id.get)
      BookmarkFactory(title = optionalTitle.getOrElse(uri.title.get), url = url, uriId = uri.id.get, userId = user.id.get, source = source).withPrivate(isPrivate)
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

  def getCollection(collectionId: Id[Collection]): Collection = {
    allCollections(collectionId)
  }

  def saveUserExperiment(experiment: UserExperiment): UserExperiment = {
    val id = experiment.id.getOrElse(nextUserExperimentId)
    val userId = experiment.userId
    val experimentWithId = experiment.withId(id)
    allUserExperiments += (userId -> (allUserExperiments.getOrElse(userId, Set.empty) + experimentWithId))
    experimentWithId
  }

  def saveComment(comment: Comment, recipientIds: Id[User]*): Comment = {
    val id = comment.id.getOrElse(nextCommentId)
    val updatedComment = comment.withId(id).copy(seq = nextCommentSeqNum)
    val commentRecipients = recipientIds match {
      case Nil => updatedComment.parent.map(allCommentRecipients(_)).getOrElse(Set.empty)
      case _ => recipientIds.map(userId => CommentRecipient(commentId = updatedComment.id.get, userId = Some(userId))).toSet
    }
    allComments += (id -> updatedComment)
    allCommentRecipients += (id -> commentRecipients)
    updatedComment
  }

  // ShoeboxServiceClient methods

  def getUserOpt(id: ExternalId[User]): Future[Option[User]] = {
    val userOpt =  allUserExternalIds.get(id)
    Promise.successful(userOpt).future
  }

  def getUser(id: Id[User]): Future[User] = {
    val user = allUsers(id)
    Promise.successful(user).future
  }

  def getNormalizedURI(uriId: Id[NormalizedURI]): Future[NormalizedURI] = {
    val uri = allNormalizedURIs(uriId)
    Promise.successful(uri).future
  }

  def getNormalizedURIs(ids: Seq[Id[NormalizedURI]]): Future[Seq[NormalizedURI]] = {
    val uris = ids.map(allNormalizedURIs(_))
    Promise.successful(uris).future
  }

  def getBookmarks(userId: Id[User]): Future[Seq[Bookmark]] = {
    val bookmarks = allUserBookmarks.getOrElse(userId, Set.empty).map(allBookmarks(_)).toSeq
    Promise.successful(bookmarks).future
  }

  def getBookmarksChanged(seqNum: SequenceNumber, fetchSize: Int): Future[Seq[Bookmark]] = {
    val bookmarks = allBookmarks.values.filter(_.seq > seqNum).toSeq.sortBy(_.seq).take(fetchSize)
    Promise.successful(bookmarks).future
  }

  def getCommentsChanged(seqNum: SequenceNumber, fetchSize: Int): Future[Seq[Comment]] = {
    val comments = allComments.values.filter(_.seq > seqNum).toSeq.sortBy(_.seq).take(fetchSize)
    Promise.successful(comments).future
  }

  def getCommentRecipientIds(commentId: Id[Comment]): Future[Seq[Id[User]]] = {
    val commentRecipientIds = allCommentRecipients.getOrElse(commentId, Set.empty).filter(_.state == CommentRecipientStates.ACTIVE).map(_.userId.get).toSeq
    Promise.successful(commentRecipientIds).future
  }


  def persistServerSearchEvent(metaData: JsObject): Unit ={
    //EventPersister.persist(Events.serverEvent(EventFamilies.SERVER_SEARCH, "search_return_hits", metaData.as[JsObject])(clock, fortyTwoServices))
  }

  def getClickHistoryFilter(userId: Id[User]) = {
    Promise.successful(clickHistoryTracker.getMultiHashFilter(userId).getFilter).future
  }

  def getBrowsingHistoryFilter(userId: Id[User]) = {
    Promise.successful(browsingHistoryTracker.getMultiHashFilter(userId).getFilter).future
  }

  def getConnectedUsers(userId: Id[User]): Future[Set[Id[User]]] = {
    val connectedUsers = allUserConnections.getOrElse(userId, Set.empty)
    Promise.successful(connectedUsers).future
  }

  def reportArticleSearchResult(res: ArticleSearchResult): Unit = {}

  def getUsers(userIds: Seq[Id[User]]): Future[Seq[User]] = {
    val users = userIds.map(allUsers(_))
    Promise.successful(users).future
  }

  def getUserIdsByExternalIds(extIds: Seq[ExternalId[User]]): Future[Seq[Id[User]]] = {
    val ids = extIds.map(allUserExternalIds(_).id.get)
    Promise.successful(ids).future
  }

  def getBasicUsers(userIds: Seq[Id[User]]): Future[Map[Id[User], BasicUser]] = {
    val basicUsers = userIds.map { id =>
      val user = allUsers(id)
      id -> BasicUser(
        externalId = user.externalId,
        firstName = user.firstName,
        lastName = user.lastName,
        networkIds = allSocialUserInfos(id).map {su => su.networkType -> su.socialId }.toMap,
        pictureName = "fake.jpg" //
      )
    }.toMap
    Promise.successful(basicUsers).future
  }

  def sendMail(email: com.keepit.common.mail.ElectronicMail): Future[Boolean] = ???
  def getPhrasesByPage(page: Int, size: Int): Future[Seq[Phrase]] = Promise.successful(Seq()).future
  def getSocialUserInfoByNetworkAndSocialId(id: SocialId, networkType: SocialNetworkType): Future[Option[SocialUserInfo]] = ???
  def getSessionByExternalId(sessionId: com.keepit.common.db.ExternalId[com.keepit.model.UserSession]): scala.concurrent.Future[Option[com.keepit.model.UserSession]] = ???
  def getSocialUserInfosByUserId(userId: com.keepit.common.db.Id[com.keepit.model.User]): scala.concurrent.Future[List[com.keepit.model.SocialUserInfo]] = ???

  def getCollectionsChanged(seqNum: SequenceNumber, fetchSize: Int): Future[Seq[(Id[Collection], Id[User], SequenceNumber)]] = {
    val collections = allCollections.values.filter(_.seq > seqNum).toSeq.sortBy(_.seq).take(fetchSize)
    val summarizedCollections = collections.map { c => (c.id.get, c.userId, c.seq) }
    Promise.successful(summarizedCollections).future
  }

  def getBookmarksInCollection(collectionId: Id[Collection]): Future[Seq[Bookmark]] = {
    val bookmarks = allCollectionBookmarks(collectionId).map(allBookmarks(_)).toSeq
    Promise.successful(bookmarks).future
  }

  def getCollectionsByUser(userId: Id[User]): Future[Seq[Id[Collection]]] = {
    val collections = allCollections.values.filter(_.userId == userId).map(_.id.get).toSeq
    Promise.successful(collections).future
  }

  def getCollectionIdsByExternalIds(collIds: Seq[ExternalId[Collection]]): Future[Seq[Id[Collection]]] = ???

  def getIndexable(seqNum: Long, fetchSize: Int = -1) : Future[Seq[NormalizedURI]] = {
    val uris = allNormalizedURIs.values.filter(_.seq > SequenceNumber(seqNum)).toSeq.sortBy(_.seq)
    val fewerUris = (if (fetchSize >= 0) uris.take(fetchSize) else uris)
    Promise.successful(fewerUris).future
  }

  def getBookmarkByUriAndUser(uriId: Id[NormalizedURI], userId: Id[User]): Future[Option[Bookmark]] = {
    val bookmark = allUserBookmarks(userId).map(allBookmarks(_)).find(_.uriId == uriId)
    Promise.successful(bookmark).future
  }

  def getActiveExperiments: Future[Seq[SearchConfigExperiment]] = {
    val exp = allSearchExperiments.values.filter(_.isActive).toSeq
    Promise.successful(exp).future
  }

  def getExperiments: Future[Seq[SearchConfigExperiment]] = {
    val exp = allSearchExperiments.values.filter(_.state != SearchConfigExperimentStates.ACTIVE).toSeq
    Promise.successful(exp).future
  }

  def getExperiment(id: Id[SearchConfigExperiment]): Future[SearchConfigExperiment] = {
    val exp = allSearchExperiments(id)
    Promise.successful(exp).future
  }

  def saveExperiment(experiment: SearchConfigExperiment): Future[SearchConfigExperiment] = {
    val id = experiment.id.getOrElse(nextSearchExperimentId)
    val experimentWithId = experiment.withId(id)
    allSearchExperiments += (experimentWithId.id.get -> experimentWithId)
    Promise.successful(experimentWithId).future
  }

  def hasExperiment(userId: Id[User], state: State[ExperimentType]): Future[Boolean] = {
    val has = allUserExperiments.getOrElse(userId, Set.empty).exists(exp => exp.experimentType == state && exp.state == UserExperimentStates.ACTIVE)
    Promise.successful(has).future
  }

  def getUserExperiments(userId: Id[User]): Future[Seq[State[ExperimentType]]] = {
    val states = allUserExperiments.getOrElse(userId, Set.empty).filter(_.state == UserExperimentStates.ACTIVE).map(_.experimentType).toSeq
    Promise.successful(states).future
  }

}

class FakeClickHistoryTrackerImpl (tableSize: Int, numHashFuncs: Int, minHits: Int) extends ClickHistoryTracker with Logging {
  var allUserClickHistories = Map[Id[User], ClickHistory]()

  def add(userId: Id[User], uriId: Id[NormalizedURI]) = {
    val filter = getMultiHashFilter(userId)
    filter.put(uriId.id)

    val userClickHistory = allUserClickHistories.get(userId) match {
      case Some(ch) =>
        ch.withFilter(filter.getFilter)
      case None =>
        ClickHistory(userId = userId, tableSize = tableSize, filter = filter.getFilter, numHashFuncs = numHashFuncs, minHits = minHits)
    }
    allUserClickHistories += (userId -> userClickHistory)
    userClickHistory
  }

  def getMultiHashFilter(userId: Id[User]): MultiHashFilter[ClickHistory] = {
    allUserClickHistories.get(userId) match {
      case Some(clickHistory) =>
        new MultiHashFilter[ClickHistory](clickHistory.tableSize, clickHistory.filter, clickHistory.numHashFuncs, clickHistory.minHits)
      case None =>
        val filter = MultiHashFilter[ClickHistory](tableSize, numHashFuncs, minHits)
        filter
    }
  }
}

class FakeBrowsingHistoryTrackerImpl (tableSize: Int, numHashFuncs: Int, minHits: Int) extends BrowsingHistoryTracker with Logging {
  var allUserBrowsingHistories = Map[Id[User], BrowsingHistory]()

  def add(userId: Id[User], uriId: Id[NormalizedURI]) = {
    val filter = getMultiHashFilter(userId)
    filter.put(uriId.id)

    val userBrowsingHistory = allUserBrowsingHistories.get(userId) match {
        case Some(bh) =>
          bh.withFilter(filter.getFilter)
        case None =>
          BrowsingHistory(userId = userId, tableSize = tableSize, filter = filter.getFilter, numHashFuncs = numHashFuncs, minHits = minHits)

    }
    allUserBrowsingHistories += (userId -> userBrowsingHistory)
    userBrowsingHistory
  }

  def getMultiHashFilter(userId: Id[User]): MultiHashFilter[BrowsingHistory] = {
    allUserBrowsingHistories.get(userId) match {
      case Some(browsingHistory) =>
        new MultiHashFilter[BrowsingHistory](browsingHistory.tableSize, browsingHistory.filter, browsingHistory.numHashFuncs, browsingHistory.minHits)
      case None =>
        val filter = MultiHashFilter[BrowsingHistory](tableSize, numHashFuncs, minHits)
        filter
    }
  }
}


