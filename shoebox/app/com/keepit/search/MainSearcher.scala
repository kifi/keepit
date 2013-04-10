package com.keepit.search

import com.keepit.search.graph.URIGraphSearcher
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
import com.keepit.search.query.{TopLevelQuery,QueryUtil}
import com.keepit.search.query.parser.SpellCorrector
import com.keepit.common.analytics.{EventFamilies, Events, PersistEventPlugin}
import com.keepit.common.time._
import com.keepit.common.controller.FortyTwoServices
import play.api.libs.json._
import java.util.UUID
import scala.math._
import org.joda.time.DateTime


class MainSearcher(
    userId: Id[User],
    friendIds: Set[Id[User]],
    filter: SearchFilter,
    config: SearchConfig,
    articleSearcher: Searcher,
    val uriGraphSearcher: URIGraphSearcher,
    parserFactory: MainQueryParserFactory,
    resultClickTracker: ResultClickTracker,
    browsingHistoryTracker: BrowsingHistoryTracker,
    clickHistoryTracker: ClickHistoryTracker,
    persistEventPlugin: PersistEventPlugin,
    spellCorrector: SpellCorrector)
    (implicit private val clock: Clock,
    private val fortyTwoServices: FortyTwoServices
) extends Logging {
  val currentTime = currentDateTime.getMillis()
  val idFilter = filter.idFilter
  val isInitialSearch = idFilter.isEmpty

  // get config params
  val sharingBoostInNetwork = config.asFloat("sharingBoostInNetwork")
  val sharingBoostOutOfNetwork = config.asFloat("sharingBoostOutOfNetwork")
  val percentMatch = config.asFloat("percentMatch")
  val recencyBoost = config.asFloat("recencyBoost")
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
  val siteBoost = config.asFloat("siteBoost")
  val minMyBookmarks = config.asInt("minMyBookmarks")
  val myBookmarkBoost = config.asFloat("myBookmarkBoost")
  val maxResultClickBoost = config.asFloat("maxResultClickBoost")

  // tailCutting is set to low when a non-default filter is in use
  val tailCutting = if (filter.isDefault && isInitialSearch) config.asFloat("tailCutting") else 0.001f

  // initialize user's social graph info
  private[this] val myUriEdges = uriGraphSearcher.getUserToUriEdgeSetWithCreatedAt(userId, publicOnly = false)
  private[this] val myUris = myUriEdges.destIdLongSet
  private[this] val myPublicUris = uriGraphSearcher.getUserToUriEdgeSet(userId, publicOnly = true).destIdLongSet
  private[this] val filteredFriendIds = filter.filterFriends(friendIds)
  private[this] val friendUris = filteredFriendIds.foldLeft(Set.empty[Long]){ (s, f) =>
    s ++ uriGraphSearcher.getUserToUriEdgeSet(f, publicOnly = true).destIdLongSet
  }
  private[this] val friendlyUris = {
    if (filter.includeMine) myUris ++ friendUris
    else if (filter.includeShared) friendUris
    else friendUris -- myUris // friends only
  }

  private[this] val customFilterOn = (filteredFriendIds != friendIds)
  private[this] val friendEdgeSet = uriGraphSearcher.getUserToUserEdgeSet(userId, friendIds)
  private[this] val filteredFriendEdgeSet = if (customFilterOn) uriGraphSearcher.getUserToUserEdgeSet(userId, filteredFriendIds) else friendEdgeSet



  def findSharingUsers(id: Id[NormalizedURI]) = {
    val sharingUsers = uriGraphSearcher.intersect(friendEdgeSet, uriGraphSearcher.getUriToUserEdgeSet(id)).destIdSet
    val effectiveSharingSize = if (customFilterOn) filter.filterFriends(sharingUsers).size else sharingUsers.size
    (sharingUsers, effectiveSharingSize)
  }

  def getPersonalizedSearcher(query: Query) = {
    val indexReader = uriGraphSearcher.openPersonalIndex(userId, query) match {
      case Some((personalReader, personalIdMapper)) =>
        articleSearcher.indexReader.add(personalReader, personalIdMapper)
      case None =>
        articleSearcher.indexReader
    }
    PersonalizedSearcher(userId, indexReader, myUris, browsingHistoryTracker, clickHistoryTracker, svWeightMyBookMarks, svWeightBrowsingHistory, svWeightClickHistory)
  }

  def searchText(queryString: String, maxTextHitsPerCategory: Int, clickBoosts: ResultClickTracker.ResultClickBoosts)(implicit lang: Lang) = {
    val myHits = createQueue(maxTextHitsPerCategory)
    val friendsHits = createQueue(maxTextHitsPerCategory)
    val othersHits = createQueue(maxTextHitsPerCategory)

    val parser = parserFactory(lang, proximityBoost, semanticBoost, phraseBoost, siteBoost)
    parser.setPercentMatch(percentMatch)
    parser.enableCoord = enableCoordinator

    val parsedQuery = parser.parse(queryString)

    parsedQuery.map{ articleQuery =>
      log.debug("articleQuery: %s".format(articleQuery.toString))

      val personalizedSearcher = getPersonalizedSearcher(articleQuery)
      personalizedSearcher.setSimilarity(similarity)
      personalizedSearcher.doSearch(articleQuery){ (scorer, mapper) =>
        var doc = scorer.nextDoc()
        while (doc != NO_MORE_DOCS) {
          val id = mapper.getId(doc)
          if (!idFilter.contains(id)) {
            val clickBoost = clickBoosts(id)
            val score = scorer.score()
            if (friendlyUris.contains(id)) {
              if (myUris.contains(id)) {
                myHits.insert(id, score * clickBoost, true, !myPublicUris.contains(id))
              } else {
                friendsHits.insert(id, score * clickBoost, false, false)
              }
            } else if (filter.includeOthers) {
              othersHits.insert(id, score * clickBoost, false, false)
            }
          }
          doc = scorer.nextDoc()
        }
      }
    }

    (myHits, friendsHits, othersHits, parsedQuery)
  }

  def search(queryString: String, numHitsToReturn: Int, lastUUID: Option[ExternalId[ArticleSearchResultRef]], filter: SearchFilter = SearchFilter.default()): ArticleSearchResult = {

    implicit val lang = Lang("en") // TODO: detect
    val now = currentDateTime
    val clickBoosts = resultClickTracker.getBoosts(userId, queryString, maxResultClickBoost)
    val (myHits, friendsHits, othersHits, parsedQuery) = searchText(queryString, maxTextHitsPerCategory = numHitsToReturn * 5, clickBoosts)

    val myTotal = myHits.totalHits
    val friendsTotal = friendsHits.totalHits

    val hits = createQueue(numHitsToReturn)

    // global high score excluding others (an orphan uri sometimes makes results disappear)
    val highScore = max(myHits.highScore, friendsHits.highScore)

    var threshold = highScore * tailCutting

    if (myHits.size > 0) {
      myHits.toRankedIterator.map{ case (h, rank) =>
        h.score = h.score * dampFunc(rank, dampingHalfDecayMine) // damping the scores by rank
        h
      }.takeWhile{ h =>
        h.score > threshold
      }.foreach{ h =>
        val id = Id[NormalizedURI](h.id)
        val (sharingUsers, effectiveSharingSize) = findSharingUsers(id)
        h.users = sharingUsers
        h.scoring = new Scoring(h.score, h.score / highScore, bookmarkScore(effectiveSharingSize + 1), recencyScore(myUriEdges.getCreatedAt(id)))
        h.score = h.scoring.score(myBookmarkBoost, sharingBoostInNetwork, recencyBoost)
        hits.insert(h)
      }
    }

    if (friendsHits.size > 0 && filter.includeFriends) {
      val queue = createQueue(numHitsToReturn - min(minMyBookmarks, hits.size))
      hits.drop(hits.size - minMyBookmarks).foreach{ h => queue.insert(h) }
      friendsHits.toRankedIterator.map{ case (h, rank) =>
        h.score = h.score * dampFunc(rank, dampingHalfDecayFriends) // damping the scores by rank
        h
      }.takeWhile{ h =>
        h.score > threshold
      }.foreach{ h =>
        val id = Id[NormalizedURI](h.id)
        val (sharingUsers, effectiveSharingSize) = findSharingUsers(id)
        h.users = sharingUsers
        h.scoring = new Scoring(h.score, h.score / highScore, bookmarkScore(effectiveSharingSize), 0.0f)
        h.score = h.scoring.score(1.0f, sharingBoostInNetwork, recencyBoost)
        queue.insert(h)
      }
      queue.foreach{ h => hits.insert(h) }
    }

    if (hits.size < numHitsToReturn && othersHits.size > 0 && filter.includeOthers) {
      val queue = createQueue(numHitsToReturn - hits.size)
      othersHits.toRankedIterator.map{ case (h, rank) =>
        h.score = h.score * dampFunc(rank, dampingHalfDecayOthers) // damping the scores by rank
        h
      }.takeWhile{
        h => h.score > threshold
      }.foreach{ h =>
        h.bookmarkCount = getPublicBookmarkCount(h.id) // TODO: revisit this later. We probably want the private count.
        if (h.bookmarkCount > 0) {
          h.scoring = new Scoring(h.score, h.score / highScore, bookmarkScore(h.bookmarkCount), 0.0f)
          h.score = h.scoring.score(1.0f, sharingBoostOutOfNetwork, recencyBoost)
          queue.insert(h)
        }
      }
      queue.foreach{ h => hits.insert(h) }
    }

    val hitList = hits.toSortedList
    hitList.foreach{ h => if (h.bookmarkCount == 0) h.bookmarkCount = getPublicBookmarkCount(h.id) }

    val newIdFilter = filter.idFilter ++ hitList.map(_.id)
    val (svVar,svExistVar) = SemanticVariance.svVariance(parsedQuery, hitList, this.articleSearcher, this.uriGraphSearcher);								// compute sv variance. may need to record the time elapsed.
    val millisPassed = currentDateTime.getMillis() - now.getMillis()

    val searchResultUuid = ExternalId[ArticleSearchResultRef]()

    val metaData = JsObject( Seq("queryUUID"->JsString(searchResultUuid.id), "svVariance"-> JsNumber(svVar), "svExistenceVar" -> JsNumber(svExistVar) ))
    persistEventPlugin.persist(Events.serverEvent(EventFamilies.SERVER_SEARCH, "search_return_hits", metaData))

    ArticleSearchResult(lastUUID, queryString, hitList.map(_.toArticleHit),
        myTotal, friendsTotal, !hitList.isEmpty, hitList.map(_.scoring), newIdFilter, millisPassed.toInt,
        (idFilter.size / numHitsToReturn).toInt, uuid = searchResultUuid, svVariance = svVar, svExistenceVar = svExistVar)
  }



  private def getPublicBookmarkCount(id: Long) = {
    uriGraphSearcher.getUriToUserEdgeSet(Id[NormalizedURI](id)).size
  }

  def createQueue(sz: Int) = new ArticleHitQueue(sz)

  private def dampFunc(rank: Int, halfDecay: Double) = (1.0d / (1.0d + pow(rank.toDouble/halfDecay, 3.0d))).toFloat

  private def bookmarkScore(bookmarkCount: Int) = (1.0f - (1.0f/(1.0f + bookmarkCount.toFloat)))

  private def recencyScore(createdAt: Long): Float = {
    val t = max(currentTime - createdAt, 0).toFloat / halfDecayMillis
    val t2 = t * t
    (1.0f/(1.0f + t2))
  }

  def explain(queryString: String, uriId: Id[NormalizedURI]): Option[(Query, Explanation)] = {
    val lang = Lang("en") // TODO: detect
    val parser = parserFactory(lang, proximityBoost, semanticBoost, phraseBoost, siteBoost)
    parser.setPercentMatch(percentMatch)
    parser.enableCoord = enableCoordinator

    parser.parse(queryString).map{ query =>
      var personalizedSearcher = getPersonalizedSearcher(query)
      personalizedSearcher.setSimilarity(similarity)
      (query, personalizedSearcher.explain(query, uriId.id))
    }
  }
}

class ArticleHitQueue(sz: Int) extends PriorityQueue[MutableArticleHit](sz) {

  val NO_FRIEND_IDS = Set.empty[Id[User]]

  var highScore = Float.MinValue
  var totalHits = 0

  override def lessThan(a: MutableArticleHit, b: MutableArticleHit) = (a.score < b.score || (a.score == b.score && a.id < b.id))

  var overflow: MutableArticleHit = null // sorry about the null, but this is necessary to work with lucene's priority queue efficiently

  def insert(id: Long, score: Float, isMyBookmark: Boolean, isPrivate: Boolean, friends: Set[Id[User]] = NO_FRIEND_IDS, bookmarkCount: Int = 0) {
    if (overflow == null) overflow = new MutableArticleHit(id, score, isMyBookmark, isPrivate, friends, bookmarkCount, null)
    else overflow(id, score, isMyBookmark, isPrivate, friends, bookmarkCount)

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

  def drop(n: Int): List[MutableArticleHit] = {
    var i = 0
    var dropped: List[MutableArticleHit] = Nil
    while (i < n && size > 0) {
      dropped = pop() :: dropped
      i += 1
    }
    dropped
  }

  def reset() {
    super.clear()
    highScore = Float.MinValue
    totalHits = 0
  }
}

case class ArticleHit(uriId: Id[NormalizedURI], score: Float, isMyBookmark: Boolean, isPrivate: Boolean, users: Set[Id[User]], bookmarkCount: Int)

case class ArticleSearchResult(
  last: Option[ExternalId[ArticleSearchResultRef]], // uuid of the last search. the frontend is responsible for tracking, this is meant for sessionization.
  query: String,
  hits: Seq[ArticleHit],
  myTotal: Int,
  friendsTotal: Int,
  mayHaveMoreHits: Boolean,
  scorings: Seq[Scoring],
  filter: Set[Long],
  millisPassed: Int,
  pageNumber: Int,
  uuid: ExternalId[ArticleSearchResultRef] = ExternalId(),
  time: DateTime = currentDateTime,
  svVariance: Float = -1.0f,			// semantic vector variance
  svExistenceVar: Float = -1.0f
)


class Scoring(val textScore: Float, val normalizedTextScore: Float, val bookmarkScore: Float, val recencyScore: Float) extends Equals {
  var boostedTextScore: Float = Float.NaN
  var boostedBookmarkScore: Float = Float.NaN
  var boostedRecencyScore: Float = Float.NaN

  def score(textBoost: Float, bookmarkBoost: Float, recencyBoost: Float) = {
    boostedTextScore = normalizedTextScore * textBoost
    boostedBookmarkScore = bookmarkScore * bookmarkBoost
    boostedRecencyScore = recencyScore * recencyBoost

    boostedTextScore + boostedBookmarkScore + boostedRecencyScore
  }

  override def toString() = {
    "Scoring(%f, %f, %f, %f, %f, %f, %f)".format(textScore, normalizedTextScore, bookmarkScore, recencyScore, boostedTextScore, boostedBookmarkScore, boostedRecencyScore)
  }

  def canEqual(other: Any) = {
    other.isInstanceOf[com.keepit.search.Scoring]
  }

  override def equals(other: Any) = {
    other match {
      case that: com.keepit.search.Scoring => that.canEqual(Scoring.this) && textScore == that.textScore && normalizedTextScore == that.normalizedTextScore && bookmarkScore == that.bookmarkScore
      case _ => false
    }
  }

  override def hashCode() = {
    val prime = 41
    prime * (prime * (prime + textScore.hashCode) + normalizedTextScore.hashCode) + bookmarkScore.hashCode
  }
}

// mutable hit object for efficiency
class MutableArticleHit(var id: Long, var score: Float, var isMyBookmark: Boolean, var isPrivate: Boolean, var users: Set[Id[User]], var bookmarkCount: Int, var scoring: Scoring) {
  def apply(newId: Long, newScore: Float, newIsMyBookmark: Boolean, newIsPrivate: Boolean, newUsers: Set[Id[User]], newBookmarkCount: Int) = {
    id = newId
    score = newScore
    isMyBookmark = newIsMyBookmark
    isPrivate = newIsPrivate
    users = newUsers
    bookmarkCount = newBookmarkCount
  }
  def toArticleHit = ArticleHit(Id[NormalizedURI](id), score, isMyBookmark, isPrivate, users, bookmarkCount)
}
