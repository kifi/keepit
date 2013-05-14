package com.keepit.search

import com.keepit.search.graph.URIGraphSearcher
import com.keepit.search.graph.UserToUserEdgeSet
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
import com.keepit.common.analytics.{EventFamilies, Events, PersistEventPlugin}
import com.keepit.common.time._
import com.keepit.common.service.FortyTwoServices
import play.api.libs.json._
import java.util.UUID
import scala.math._
import org.joda.time.DateTime
import com.keepit.serializer.SearchResultInfoSerializer
import com.keepit.search.query.LuceneExplanationExtractor
import com.keepit.search.query.LuceneScoreNames


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
  val phraseProximityBoost = config.asFloat("phraseProximityBoost")
  val siteBoost = config.asFloat("siteBoost")
  val minMyBookmarks = config.asInt("minMyBookmarks")
  val myBookmarkBoost = config.asFloat("myBookmarkBoost")
  val maxResultClickBoost = config.asFloat("maxResultClickBoost")

  // tailCutting is set to low when a non-default filter is in use
  val tailCutting = if (filter.isDefault && isInitialSearch) config.asFloat("tailCutting") else 0.001f

  // initialize user's social graph info
  private[this] val myUriEdges = uriGraphSearcher.myUriEdgeSet
  private[this] val myUris = myUriEdges.destIdLongSet
  private[this] val myPublicUris = uriGraphSearcher.myPublicUriEdgeSet.destIdLongSet
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

  val preparationTime = currentDateTime.getMillis() - currentTime
  log.info(s"mainSearcher preparation time: $preparationTime milliseconds")

  private def findSharingUsers(id: Id[NormalizedURI]): UserToUserEdgeSet = {
    uriGraphSearcher.intersect(friendEdgeSet, uriGraphSearcher.getUriToUserEdgeSet(id))
  }

  private def findEffectiveSharingSize(sharingUsers: UserToUserEdgeSet): Int = {
    if (customFilterOn) filter.filterFriends(sharingUsers.destIdSet).size else sharingUsers.size
  }

  def getPersonalizedSearcher(query: Query) = {
    val indexReader = uriGraphSearcher.openPersonalIndex(query) match {
      case Some((personalReader, personalIdMapper)) =>
        articleSearcher.indexReader.add(personalReader, personalIdMapper)
      case None =>
        articleSearcher.indexReader
    }
    PersonalizedSearcher(userId, indexReader, myUris, friendUris, browsingHistoryTracker, clickHistoryTracker, svWeightMyBookMarks, svWeightBrowsingHistory, svWeightClickHistory)
  }

  def searchText(queryString: String, maxTextHitsPerCategory: Int, clickBoosts: ResultClickTracker.ResultClickBoosts)(implicit lang: Lang) = {
    val myHits = createQueue(maxTextHitsPerCategory)
    val friendsHits = createQueue(maxTextHitsPerCategory)
    val othersHits = createQueue(maxTextHitsPerCategory)

    val parser = parserFactory(lang, proximityBoost, semanticBoost, phraseBoost, phraseProximityBoost, siteBoost)
    parser.setPercentMatch(percentMatch)
    parser.enableCoord = enableCoordinator

    val parsedQuery = parser.parse(queryString)

    val personalizedSearcher = parsedQuery.map{ articleQuery =>
      log.debug("articleQuery: %s".format(articleQuery.toString))

      val namedQueryContext = parser.namedQueryContext
      val semanticVectorScoreAccessor = namedQueryContext.getScoreAccessor("semantic vector")

      val personalizedSearcher = getPersonalizedSearcher(articleQuery)
      personalizedSearcher.setSimilarity(similarity)
      personalizedSearcher.doSearch(articleQuery){ (scorer, mapper) =>
        var doc = scorer.nextDoc()
        while (doc != NO_MORE_DOCS) {
          val id = mapper.getId(doc)
          if (!idFilter.contains(id)) {
            val clickBoost = clickBoosts(id)
            val score = scorer.score()
            val newSemanticScore = semanticVectorScoreAccessor.getScore(doc)
            if (friendlyUris.contains(id)) {
              if (myUris.contains(id)) {
                myHits.insert(id, score * clickBoost, true, !myPublicUris.contains(id), semanticScore = newSemanticScore)
              } else {
                friendsHits.insert(id, score * clickBoost, false, false, semanticScore = newSemanticScore)
              }
            } else if (filter.includeOthers) {
              othersHits.insert(id, score * clickBoost, false, false, semanticScore = newSemanticScore)
            }
          }
          doc = scorer.nextDoc()
        }
        namedQueryContext.reset()
      }
      personalizedSearcher
    }

    (myHits, friendsHits, othersHits, parsedQuery, personalizedSearcher)
  }

  def search(queryString: String, numHitsToReturn: Int, lastUUID: Option[ExternalId[ArticleSearchResultRef]], filter: SearchFilter = SearchFilter.default()): ArticleSearchResult = {

    implicit val lang = Lang("en") // TODO: detect
    val now = currentDateTime
    val clickBoosts = resultClickTracker.getBoosts(userId, queryString, maxResultClickBoost)
    val (myHits, friendsHits, othersHits, parsedQuery, personalizedSearcher) = searchText(queryString, maxTextHitsPerCategory = numHitsToReturn * 5, clickBoosts) match {
      case (myHits, friendsHits, othersHits, parsedQuery, personalizedSearcher) => {
        if ( myHits.size() + friendsHits.size() + othersHits.size() > 0 ) (myHits, friendsHits, othersHits, parsedQuery, personalizedSearcher)
        else {
          val alternative = try {
            spellCorrector.getAlternativeQuery(queryString)
          } catch {
            case e: Exception =>
              log.error("unexpected SpellCorrector error" )
              queryString
          }
          if (alternative.trim == queryString.trim)	(myHits, friendsHits, othersHits, parsedQuery, personalizedSearcher)
          else { log.info("spell correction was made"); searchText(alternative, maxTextHitsPerCategory = numHitsToReturn * 5, clickBoosts) }
        }
      }
    }

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
    var numCollectStats = 10

    if (myHits.size > 0) {
      myHits.toRankedIterator.forall{ case (h, rank) =>
        val id = Id[NormalizedURI](h.id)
        val sharingUsers = findSharingUsers(id)

        if (numCollectStats > 0 && sharingUsers.size > 0) {
          friendStats.add(sharingUsers.destIdLongSet, h.score)
          numCollectStats -= 1
        }

        val score = h.score * dampFunc(rank, dampingHalfDecayMine) // damping the scores by rank

        if (score > threshold) {
          h.users = sharingUsers.destIdSet
          h.scoring = new Scoring(score, score / highScore, bookmarkScore(findEffectiveSharingSize(sharingUsers) + 1), recencyScore(myUriEdges.getCreatedAt(id)))
          h.score = h.scoring.score(myBookmarkBoost, sharingBoostInNetwork, recencyBoost)
          hits.insert(h)
          true
        } else {
          numCollectStats > 0
        }
      }
    }

    if (friendsHits.size > 0 && filter.includeFriends) {
      val queue = createQueue(numHitsToReturn - min(minMyBookmarks, hits.size))
      hits.drop(hits.size - minMyBookmarks).foreach{ h => queue.insert(h) }

      friendsHits.toRankedIterator.forall{ case (h, rank) =>
        val id = Id[NormalizedURI](h.id)
        val sharingUsers = findSharingUsers(id)

        if (numCollectStats > 0) {
          friendStats.add(sharingUsers.destIdLongSet, h.score)
          numCollectStats -= 1
        }

        val score = h.score * dampFunc(rank, dampingHalfDecayFriends) // damping the scores by rank
        if (score > threshold) {
          h.users = sharingUsers.destIdSet
          h.scoring = new Scoring(score, score / highScore, bookmarkScore(findEffectiveSharingSize(sharingUsers)), 0.0f)
          h.score = h.scoring.score(1.0f, sharingBoostInNetwork, recencyBoost)
          queue.insert(h)
          true
        } else {
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
            h.scoring = new Scoring(score, score / highScore, bookmarkScore(h.bookmarkCount), 0.0f)
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

    val hitList = hits.toSortedList
    hitList.foreach{ h => if (h.bookmarkCount == 0) h.bookmarkCount = getPublicBookmarkCount(h.id) }

    val newIdFilter = filter.idFilter ++ hitList.map(_.id)
    val (svVar,svExistVar) = SemanticVariance.svVariance(parsedQuery, hitList, personalizedSearcher) // compute sv variance. may need to record the time elapsed.

    val millisPassed = currentDateTime.getMillis() - now.getMillis()

    log.info(s"queryString length: ${queryString.size}, main search time: $millisPassed milliseconds")

    // simple classifier
    val show = if (svVar > 0.17f) false else {
      val isGood = (parsedQuery, personalizedSearcher) match {
        case (query: Some[Query], searcher: Some[PersonalizedSearcher]) => classify(query.get, hitList, clickBoosts, searcher.get)
        case _ => true
      }
      isGood
    }

    val searchResultUuid = ExternalId[ArticleSearchResultRef]()
    val searchResultInfo = SearchResultInfo(myTotal, friendsTotal, othersTotal, svVar, svExistVar)
    val searchResultJson = SearchResultInfoSerializer.serializer.writes(searchResultInfo)
    val metaData = Json.obj("queryUUID" -> JsString(searchResultUuid.id), "searchResultInfo" -> searchResultJson)
    persistEventPlugin.persist(Events.serverEvent(EventFamilies.SERVER_SEARCH, "search_return_hits", metaData))

    ArticleSearchResult(lastUUID, queryString, hitList.map(_.toArticleHit(friendStats)),
        myTotal, friendsTotal, !hitList.isEmpty, hitList.map(_.scoring), newIdFilter, millisPassed.toInt,
        (idFilter.size / numHitsToReturn).toInt, uuid = searchResultUuid, svVariance = svVar, svExistenceVar = svExistVar, toShow = show)
  }

  private def classify(parsedQuery: Query, hitList: List[MutableArticleHit], clickBoosts: ResultClickTracker.ResultClickBoosts, personalizedSearcher: PersonalizedSearcher) = {
    def classify(hit: MutableArticleHit) = {
      clickBoosts(hit.id) > 1.25f || (hit.scoring.textScore >= 0.04f && hit.semanticScore >= 0.28f)
    }
    hitList.take(3).exists(classify(_))
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
    val parser = parserFactory(lang, proximityBoost, semanticBoost, phraseBoost, phraseProximityBoost, siteBoost)
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

  def insert(id: Long, score: Float, isMyBookmark: Boolean, isPrivate: Boolean, friends: Set[Id[User]] = NO_FRIEND_IDS, bookmarkCount: Int = 0, semanticScore: Float) {
    if (overflow == null) overflow = new MutableArticleHit(id, score, isMyBookmark, isPrivate, friends, bookmarkCount, null, semanticScore)
    else overflow(id, score, isMyBookmark, isPrivate, friends, bookmarkCount, semanticScore)

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

case class ArticleHit(uriId: Id[NormalizedURI], score: Float, isMyBookmark: Boolean, isPrivate: Boolean, users: Seq[Id[User]], bookmarkCount: Int)

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
  svExistenceVar: Float = -1.0f,
  toShow: Boolean = true
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
class MutableArticleHit(var id: Long, var score: Float, var isMyBookmark: Boolean, var isPrivate: Boolean, var users: Set[Id[User]], var bookmarkCount: Int, var scoring: Scoring, var semanticScore: Float) {
  def apply(newId: Long, newScore: Float, newIsMyBookmark: Boolean, newIsPrivate: Boolean, newUsers: Set[Id[User]], newBookmarkCount: Int, newSemanticScore: Float) = {
    id = newId
    score = newScore
    isMyBookmark = newIsMyBookmark
    isPrivate = newIsPrivate
    users = newUsers
    bookmarkCount = newBookmarkCount
    semanticScore = newSemanticScore
  }
  def toArticleHit(friendStats: FriendStats) = {
    val sortedUsers = users.toSeq.sortBy{ id => - friendStats.score(id) }
    ArticleHit(Id[NormalizedURI](id), score, isMyBookmark, isPrivate, sortedUsers, bookmarkCount)
  }
}
