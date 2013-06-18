package com.keepit.search

import com.keepit.search.graph.BookmarkRecord
import com.keepit.search.graph.EdgeAccessor
import com.keepit.search.graph.CollectionSearcher
import com.keepit.search.graph.URIGraphSearcher
import com.keepit.search.graph.UserToUriEdgeSet
import com.keepit.search.graph.UserToUserEdgeSet
import com.keepit.search.index.ArticleRecord
import com.keepit.search.index.Searcher
import com.keepit.search.index.PersonalizedSearcher
import com.keepit.common.db.{Id, ExternalId}
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import com.keepit.model._
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import org.apache.lucene.search.Query
import org.apache.lucene.search.Explanation
import org.apache.lucene.util.PriorityQueue
import com.keepit.search.query.QueryUtil
import com.keepit.search.query.parser.SpellCorrector
import com.keepit.common.analytics.{EventFamilies, Events}
import com.keepit.common.time._
import com.keepit.common.service.FortyTwoServices
import play.api.libs.json._
import java.util.UUID
import scala.math._
import org.joda.time.DateTime
import com.keepit.serializer.SearchResultInfoSerializer
import com.keepit.search.query.LuceneExplanationExtractor
import com.keepit.search.query.LuceneScoreNames
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.shoebox.ClickHistoryTracker
import com.keepit.shoebox.BrowsingHistoryTracker
import scala.concurrent.Future
import com.keepit.common.akka.MonitoredAwait
import play.modules.statsd.api.Statsd


class MainSearcher(
    userId: Id[User],
    filter: SearchFilter,
    config: SearchConfig,
    articleSearcher: Searcher,
    val uriGraphSearcher: URIGraphSearcher,
    val collectionSearcher: CollectionSearcher,
    parserFactory: MainQueryParserFactory,
    resultClickTracker: ResultClickTracker,
    browsingHistoryFuture: Future[MultiHashFilter[BrowsingHistory]],
    clickHistoryFuture: Future[MultiHashFilter[ClickHistory]],
    shoeboxClient: ShoeboxServiceClient,
    spellCorrector: SpellCorrector,
    monitoredAwait: MonitoredAwait)
    (implicit private val clock: Clock,
    private val fortyTwoServices: FortyTwoServices
) extends Logging {
  val currentTime = currentDateTime.getMillis()
  val timeLogs = new MutableSearchTimeLogs()
  val idFilter = filter.idFilter
  val isInitialSearch = idFilter.isEmpty

  // get config params
  val newContentDiscoveryThreshold = config.asFloat("newContentDiscoveryThreshold")
  val sharingBoostInNetwork = config.asFloat("sharingBoostInNetwork")
  val sharingBoostOutOfNetwork = config.asFloat("sharingBoostOutOfNetwork")
  val percentMatch = config.asFloat("percentMatch")
  val recencyBoost = config.asFloat("recencyBoost")
  val newContentBoost = config.asFloat("newContentBoost")
  val halfDecayMillis = config.asFloat("halfDecayHours") * (60.0f * 60.0f * 1000.0f) // hours to millis
  val proximityBoost = config.asFloat("proximityBoost")
  val semanticBoost = config.asFloat("semanticBoost")
  val dampingHalfDecayMine = config.asFloat("dampingHalfDecayMine")
  val dampingHalfDecayFriends = config.asFloat("dampingHalfDecayFriends")
  val dampingHalfDecayOthers = config.asFloat("dampingHalfDecayOthers")
  val svWeightMyBookMarks = config.asInt("svWeightMyBookMarks")
  val svWeightBrowsingHistory = config.asInt("svWeightBrowsingHistory")
  val svWeightClickHistory = config.asInt("svWeightClickHistory")
  val similarity = Similarity(config.asString("similarity"))
  val enableCoordinator = config.asBoolean("enableCoordinator")
  val phraseBoost = config.asFloat("phraseBoost")
  val phraseProximityBoost = config.asFloat("phraseProximityBoost")
  val siteBoost = config.asFloat("siteBoost")
  val minMyBookmarks = config.asInt("minMyBookmarks")
  val myBookmarkBoost = config.asFloat("myBookmarkBoost")
  val maxResultClickBoost = config.asFloat("maxResultClickBoost")

  // tailCutting is set to low when a non-default filter is in use
  val tailCutting = if (filter.isDefault && isInitialSearch) config.asFloat("tailCutting") else 0.001f

  // initialize user's social graph info
  private[this] val myUriEdges = uriGraphSearcher.myUriEdgeSet
  private[this] val myUriEdgeAccessor = myUriEdges.accessor
  private[this] val myUris =
    filter.timeRange match {
      case Some(timeRange) =>
        filter.collections match {
          case Some(collections) =>
            collections.foldLeft(Set.empty[Long]){ (s, collId) =>
              s ++ collectionSearcher.getCollectionToUriEdgeSet(collId).filterByTimeRange(timeRange.start, timeRange.end).destIdLongSet
            }
          case _ => myUriEdges.filterByTimeRange(timeRange.start, timeRange.end).destIdLongSet
        }
      // no time range
      case _ =>
        filter.collections match {
          case Some(collections) =>
            collections.foldLeft(Set.empty[Long]){ (s, collId) =>
              s ++ collectionSearcher.getCollectionToUriEdgeSet(collId).destIdLongSet
            }
          case _ => myUriEdges.destIdLongSet
        }
    }

  private[this] val friendEdgeSet = uriGraphSearcher.friendEdgeSet
  private[this] val friendIds = friendEdgeSet.destIdSet
  private[this] val friendsUriEdgeSets = uriGraphSearcher.friendsUriEdgeSets
  private[this] val friendsUriEdgeAccessors = friendsUriEdgeSets.mapValues{ _.accessor }
  private[this] val filteredFriendIds = filter.filterFriends(friendIds)
  private[this] val filteredFriendEdgeSet = if (filter.isCustom) uriGraphSearcher.getUserToUserEdgeSet(userId, filteredFriendIds) else friendEdgeSet
  private[this] val friendUris = {
    filter.timeRange match {
      case Some(timeRange) =>
        filteredFriendIds.foldLeft(Set.empty[Long]){ (s, f) =>
          s ++ friendsUriEdgeSets(f.id).filterByTimeRange(timeRange.start, timeRange.end).destIdLongSet
        }
      case _ =>
        filteredFriendIds.foldLeft(Set.empty[Long]){ (s, f) =>
          s ++ friendsUriEdgeSets(f.id).destIdLongSet
        }
    }
  }

  private[this] val friendlyUris = {
    if (filter.includeMine) friendUris ++ myUris
    else if (filter.includeShared) friendUris
    else friendUris -- myUris // friends only
  }

  val preparationTime = currentDateTime.getMillis() - currentTime
  timeLogs.socialGraphInfo = preparationTime
  Statsd.timing("mainSearch.socialGraphInfo", preparationTime)

  private def findSharingUsers(id: Long): UserToUserEdgeSet = {
    uriGraphSearcher.intersect(friendEdgeSet, uriGraphSearcher.getUriToUserEdgeSet(Id[NormalizedURI](id)))
  }

  private def sharingScore(sharingUsers: UserToUserEdgeSet): Float = {
    if (filter.isCustom) filter.filterFriends(sharingUsers.destIdSet).size.toFloat else sharingUsers.size.toFloat
  }

  private def sharingScore(sharingUsers: UserToUserEdgeSet, normalizedFriendStats: FriendStats): Float = {
    val users = if (filter.isCustom) filter.filterFriends(sharingUsers.destIdSet) else sharingUsers.destIdSet
    users.foldLeft(0.0f){ (score, id) => score + normalizedFriendStats.score(id) }
  }

  def getPersonalizedSearcher(query: Query) = {
    val indexReader = uriGraphSearcher.openPersonalIndex(query) match {
      case Some((personalReader, personalIdMapper)) =>
        articleSearcher.indexReader.add(personalReader, personalIdMapper)
      case None =>
        articleSearcher.indexReader
    }
    PersonalizedSearcher(userId, indexReader, myUris, friendUris, browsingHistoryFuture, clickHistoryFuture, svWeightMyBookMarks, svWeightBrowsingHistory, svWeightClickHistory, shoeboxClient, monitoredAwait)
  }

  def searchText(queryString: String, maxTextHitsPerCategory: Int, clickBoosts: ResultClickTracker.ResultClickBoosts)(implicit lang: Lang) = {
    val myHits = createQueue(maxTextHitsPerCategory)
    val friendsHits = createQueue(maxTextHitsPerCategory)
    val othersHits = createQueue(maxTextHitsPerCategory)
    val t1 = currentDateTime.getMillis()

    val parser = parserFactory(lang, proximityBoost, semanticBoost, phraseBoost, phraseProximityBoost, siteBoost)
    parser.setPercentMatch(percentMatch)
    parser.enableCoord = enableCoordinator

    val parsedQuery = parser.parse(queryString)
    timeLogs.queryParsing = currentDateTime.getMillis() - t1
    Statsd.timing("mainSearch.queryParsing", timeLogs.queryParsing)

    val t2 = currentDateTime.getMillis()

    val personalizedSearcher = parsedQuery.map{ articleQuery =>
      log.debug("articleQuery: %s".format(articleQuery.toString))

      val namedQueryContext = parser.namedQueryContext
      val semanticVectorScoreAccessor = namedQueryContext.getScoreAccessor("semantic vector")

      val personalizedSearcher = getPersonalizedSearcher(articleQuery)
      personalizedSearcher.setSimilarity(similarity)
      timeLogs.personalizedSearcher = currentDateTime.getMillis() - t2
      Statsd.timing("mainSearch.personalizedSearcher", timeLogs.personalizedSearcher)
      val t3 = currentDateTime.getMillis()
      personalizedSearcher.doSearch(articleQuery){ (scorer, mapper) =>
        var doc = scorer.nextDoc()
        while (doc != NO_MORE_DOCS) {
          val id = mapper.getId(doc)
          if (!idFilter.contains(id)) {
            val clickBoost = clickBoosts(id)
            val score = scorer.score()
            val newSemanticScore = semanticVectorScoreAccessor.getScore(doc)
            if (friendlyUris.contains(id)) {
              if (myUriEdgeAccessor.seek(id)) {
                myHits.insert(id, score * clickBoost, score, newSemanticScore, clickBoost, true, !myUriEdgeAccessor.isPublic)
              } else {
                friendsHits.insert(id, score * clickBoost, score, newSemanticScore, clickBoost, false, false)
              }
            } else if (filter.includeOthers) {
              othersHits.insert(id, score * clickBoost, score, newSemanticScore, clickBoost, false, false)
            }
          }
          doc = scorer.nextDoc()
        }
        namedQueryContext.reset()
      }
      timeLogs.search = currentDateTime.getMillis() - t3
      Statsd.timing("mainSearch.LuceneSearch", timeLogs.search)
      personalizedSearcher
    }
    (myHits, friendsHits, othersHits, parsedQuery, personalizedSearcher)
  }

  def search(queryString: String, numHitsToReturn: Int, lastUUID: Option[ExternalId[ArticleSearchResultRef]], filter: SearchFilter = SearchFilter.default()): ArticleSearchResult = {

    implicit val lang = Lang("en") // TODO: detect
    val now = currentDateTime
    val clickBoosts = resultClickTracker.getBoosts(userId, queryString, maxResultClickBoost)
    timeLogs.getClickBoost = currentDateTime.getMillis() - now.getMillis()
    Statsd.timing("mainSearch.getClickboost", timeLogs.getClickBoost)
    val (myHits, friendsHits, othersHits, parsedQuery, personalizedSearcher) = searchText(queryString, maxTextHitsPerCategory = numHitsToReturn * 5, clickBoosts)
    val t1 = currentDateTime.getMillis()
    val myTotal = myHits.totalHits
    val friendsTotal = friendsHits.totalHits
    val othersTotal = othersHits.totalHits

    val hits = createQueue(numHitsToReturn)

    // compute high score excluding others (an orphan uri sometimes makes results disappear)
    // and others high score (used for tailcutting of others hits)
    val (highScore, othersHighScore) = {
      var highScore = max(myHits.highScore, friendsHits.highScore)
      val othersHighScore = max(othersHits.highScore, highScore)
      if (highScore < 0.0f) highScore = othersHighScore
      (highScore, othersHighScore)
    }

    val threshold = highScore * tailCutting
    val friendStats = FriendStats(friendIds)
    var numCollectStats = 20

    if (myHits.size > 0) {
      myHits.toRankedIterator.forall{ case (h, rank) =>
        val sharingUsers = findSharingUsers(h.id)

        if (numCollectStats > 0 && sharingUsers.size > 0) {
          val createdAt = myUriEdgeAccessor.getCreatedAt(h.id)
          val sharingUserIdSet = sharingUsers.destIdLongSet
          val earlyKeepersUserIdSet = sharingUserIdSet.filter{ f => friendsUriEdgeAccessors(f).getCreatedAt(h.id) < createdAt }
          friendStats.add(sharingUserIdSet, h.score)
          friendStats.add(earlyKeepersUserIdSet, h.score * 0.5f)
          numCollectStats -= 1
        }

        val score = h.score * dampFunc(rank, dampingHalfDecayMine) // damping the scores by rank

        if (score > threshold) {
          h.users = sharingUsers.destIdSet
          h.scoring = new Scoring(score, score / highScore, bookmarkScore(sharingScore(sharingUsers) + 1.0f), recencyScore(myUriEdgeAccessor.getCreatedAt(h.id)))
          h.score = h.scoring.score(myBookmarkBoost, sharingBoostInNetwork, recencyBoost)
          hits.insert(h)
          true
        } else {
          numCollectStats > 0
        }
      }
    }

    var newContent: Option[MutableArticleHit] = None // hold a document most recently introduced to the network

    if (friendsHits.size > 0 && filter.includeFriends) {
      val queue = createQueue(numHitsToReturn - min(minMyBookmarks, hits.size))
      hits.discharge(hits.size - minMyBookmarks).foreach{ h => queue.insert(h) }

      val normalizedFriendStats = friendStats.normalize
      var newContentScore = newContentDiscoveryThreshold
      friendsHits.toRankedIterator.forall{ case (h, rank) =>
        val sharingUsers = findSharingUsers(h.id)

        var recencyScoreVal = 0.0f
        if (numCollectStats > 0) {
          val sharingUserIdSet = sharingUsers.destIdLongSet
          val introducedAt = sharingUserIdSet.map{ f => friendsUriEdgeAccessors(f).getCreatedAt(h.id) }.min // oldest keep time
          recencyScoreVal = recencyScore(introducedAt)
          friendStats.add(sharingUserIdSet, h.score)
          numCollectStats -= 1
        }

        val score = h.score * dampFunc(rank, dampingHalfDecayFriends) // damping the scores by rank
        if (score > threshold) {
          h.users = sharingUsers.destIdSet
          h.scoring = new Scoring(score, score / highScore, bookmarkScore(sharingScore(sharingUsers, normalizedFriendStats)), recencyScoreVal)
          h.score = h.scoring.score(1.0f, sharingBoostInNetwork, newContentBoost)
          queue.insert(h)
          true
        } else {
          if (recencyScoreVal > newContentScore) {
            h.users = sharingUsers.destIdSet
            h.scoring = new Scoring(score, score / highScore, bookmarkScore(sharingScore(sharingUsers, normalizedFriendStats)), recencyScoreVal)
            h.score = h.scoring.score(1.0f, sharingBoostInNetwork, newContentBoost)
            newContent = Some(h)
            newContentScore = recencyScoreVal
          }
          numCollectStats > 0
        }
      }
      queue.foreach{ h => hits.insert(h) }
    }

    if (hits.size < numHitsToReturn && othersHits.size > 0 && filter.includeOthers) {
      val othersThreshold = othersHighScore * tailCutting
      val queue = createQueue(numHitsToReturn - hits.size)

      othersHits.toRankedIterator.forall{ case (h, rank) =>
        val score = h.score * dampFunc(rank, dampingHalfDecayOthers) // damping the scores by rank
        if (score > othersThreshold) {
          h.bookmarkCount = getPublicBookmarkCount(h.id) // TODO: revisit this later. We probably want the private count.
          if (h.bookmarkCount > 0) {
            h.scoring = new Scoring(score, score / highScore, bookmarkScore(h.bookmarkCount.toFloat), 0.0f)
            h.score = h.scoring.score(1.0f, sharingBoostOutOfNetwork, recencyBoost)
            queue.insert(h)
          }
          true
        } else {
          false
        }
      }
      queue.foreach{ h => hits.insert(h) }
    }

    var hitList = hits.toSortedList
    hitList.foreach{ h => if (h.bookmarkCount == 0) h.bookmarkCount = getPublicBookmarkCount(h.id) }

    val (svVar,svExistVar) = SemanticVariance.svVariance(parsedQuery, hitList, personalizedSearcher) // compute sv variance. may need to record the time elapsed.

    // instance a new content if any
    newContent.foreach { h =>
      if (h.bookmarkCount == 0) h.bookmarkCount = getPublicBookmarkCount(h.id)
      hitList = (hitList.take(numHitsToReturn - 1) :+ h)
    }

    val newIdFilter = filter.idFilter ++ hitList.map(_.id)

    timeLogs.processHits = currentDateTime.getMillis() - t1
    Statsd.timing("mainSearch.processHits", timeLogs.processHits)
    val millisPassed = currentDateTime.getMillis() - now.getMillis()
    timeLogs.total = millisPassed
    Statsd.timing("mainSearch.total", millisPassed)

    // simple classifier
    val show = if (svVar > 0.17f) false else {
      val isGood = (parsedQuery, personalizedSearcher) match {
        case (query: Some[Query], searcher: Some[PersonalizedSearcher]) => classify(query.get, hitList, searcher.get)
        case _ => true
      }
      isGood
    }

    val searchResultUuid = ExternalId[ArticleSearchResultRef]()
    val searchResultInfo = SearchResultInfo(myTotal, friendsTotal, othersTotal, svVar, svExistVar)
    val searchResultJson = SearchResultInfoSerializer.serializer.writes(searchResultInfo)
    val metaData = Json.obj("queryUUID" -> JsString(searchResultUuid.id), "searchResultInfo" -> searchResultJson)
    shoeboxClient.persistServerSearchEvent(metaData)
    ArticleSearchResult(lastUUID, queryString, hitList.map(_.toArticleHit(friendStats)),
        myTotal, friendsTotal, !hitList.isEmpty, hitList.map(_.scoring), newIdFilter, millisPassed.toInt,
        (idFilter.size / numHitsToReturn).toInt, uuid = searchResultUuid, svVariance = svVar, svExistenceVar = svExistVar, toShow = show, timeLogs = Some(timeLogs.toSearchTimeLogs))
  }

  private def classify(parsedQuery: Query, hitList: List[MutableArticleHit], personalizedSearcher: PersonalizedSearcher) = {
    def classify(hit: MutableArticleHit) = {
      (hit.clickBoost) > 1.25f || hit.scoring.recencyScore > 0.25f || hit.scoring.textScore > 0.7f || (hit.scoring.textScore >= 0.04f && hit.semanticScore >= 0.28f)
    }
    hitList.take(3).exists(classify(_))
  }

  private def getPublicBookmarkCount(id: Long) = {
    uriGraphSearcher.getUriToUserEdgeSet(Id[NormalizedURI](id)).size
  }

  def createQueue(sz: Int) = new ArticleHitQueue(sz)

  private def dampFunc(rank: Int, halfDecay: Double) = (1.0d / (1.0d + pow(rank.toDouble/halfDecay, 3.0d))).toFloat

  private def bookmarkScore(bookmarkCount: Float) = (1.0f - (1.0f/(1.0f + bookmarkCount)))

  private def recencyScore(createdAt: Long): Float = {
    val t = max(currentTime - createdAt, 0).toFloat / halfDecayMillis
    val t2 = t * t
    (1.0f/(1.0f + t2))
  }

  def explain(queryString: String, uriId: Id[NormalizedURI]): Option[(Query, Explanation)] = {
    val lang = Lang("en") // TODO: detect
    val parser = parserFactory(lang, proximityBoost, semanticBoost, phraseBoost, phraseProximityBoost, siteBoost)
    parser.setPercentMatch(percentMatch)
    parser.enableCoord = enableCoordinator

    parser.parse(queryString).map{ query =>
      var personalizedSearcher = getPersonalizedSearcher(query)
      personalizedSearcher.setSimilarity(similarity)
      (query, personalizedSearcher.explain(query, uriId.id))
    }
  }

  def getArticleRecord(uriId: Id[NormalizedURI]): Option[ArticleRecord] = {
    import com.keepit.search.index.ArticleRecordSerializer._
    articleSearcher.getDecodedDocValue[ArticleRecord]("rec", uriId.id)
  }

  def getBookmarkRecord(uriId: Id[NormalizedURI]): Option[BookmarkRecord] = uriGraphSearcher.getBookmarkRecord(uriId)
}

class ArticleHitQueue(sz: Int) extends PriorityQueue[MutableArticleHit](sz) {

  val NO_FRIEND_IDS = Set.empty[Id[User]]

  var highScore = Float.MinValue
  var totalHits = 0

  override def lessThan(a: MutableArticleHit, b: MutableArticleHit) = (a.score < b.score || (a.score == b.score && a.id < b.id))

  var overflow: MutableArticleHit = null // sorry about the null, but this is necessary to work with lucene's priority queue efficiently

  def insert(id: Long, score: Float, textScore: Float, semanticScore: Float, clickBoost: Float, isMyBookmark: Boolean, isPrivate: Boolean, friends: Set[Id[User]] = NO_FRIEND_IDS, bookmarkCount: Int = 0) {
    if (overflow == null) overflow = new MutableArticleHit(id, score, textScore, semanticScore, clickBoost, isMyBookmark, isPrivate, friends, bookmarkCount, null)
    else overflow(id, score, textScore, semanticScore, clickBoost, isMyBookmark, isPrivate, friends, bookmarkCount)

    if (score > highScore) highScore = score

    overflow = insertWithOverflow(overflow)
    totalHits += 1
  }

  def insert(hit: MutableArticleHit) {
    if (hit.score > highScore) highScore = hit.score
    overflow = insertWithOverflow(hit)
    totalHits += 1
  }

  // the following method is destructive. after the call ArticleHitQueue is unusable
  def toSortedList: List[MutableArticleHit] = {
    var res: List[MutableArticleHit] = Nil
    var i = size()
    while (i > 0) {
      i -= 1
      res = pop() :: res
    }
    res
  }

  // the following method is destructive. after the call ArticleHitQueue is unusable
  def toRankedIterator = toSortedList.iterator.zipWithIndex

  def foreach(f: MutableArticleHit => Unit) {
    val arr = getHeapArray()
    val sz = size()
    var i = 1
    while (i <= sz) {
      f(arr(i).asInstanceOf[MutableArticleHit])
      i += 1
    }
  }

  def discharge(n: Int): List[MutableArticleHit] = {
    var i = 0
    var discharged: List[MutableArticleHit] = Nil
    while (i < n && size > 0) {
      discharged = pop() :: discharged
      i += 1
    }
    discharged
  }

  def reset() {
    super.clear()
    highScore = Float.MinValue
    totalHits = 0
  }
}



// mutable hit object for efficiency
class MutableArticleHit(var id: Long, var score: Float, var luceneScore: Float, var semanticScore: Float, var clickBoost: Float, var isMyBookmark: Boolean, var isPrivate: Boolean, var users: Set[Id[User]], var bookmarkCount: Int, var scoring: Scoring) {
  def apply(newId: Long, newScore: Float, newTextScore: Float, newSemanticScore: Float, newClickBoost: Float, newIsMyBookmark: Boolean, newIsPrivate: Boolean, newUsers: Set[Id[User]], newBookmarkCount: Int) = {
    id = newId
    score = newScore
    luceneScore = newTextScore
    semanticScore = newSemanticScore
    clickBoost = newClickBoost
    isMyBookmark = newIsMyBookmark
    isPrivate = newIsPrivate
    users = newUsers
    bookmarkCount = newBookmarkCount
  }
  def toArticleHit(friendStats: FriendStats) = {
    val sortedUsers = users.toSeq.sortBy{ id => - friendStats.score(id) }
    ArticleHit(Id[NormalizedURI](id), score, isMyBookmark, isPrivate, sortedUsers, bookmarkCount)
  }
}

case class MutableSearchTimeLogs(
    var socialGraphInfo: Long = 0,
    var getClickBoost: Long = 0,
    var queryParsing: Long = 0,
    var personalizedSearcher: Long = 0,
    var search: Long = 0,
    var processHits: Long = 0,
    var total: Long = 0
) {
  def toSearchTimeLogs = SearchTimeLogs(socialGraphInfo, getClickBoost, queryParsing, personalizedSearcher, search, processHits, total)
}
