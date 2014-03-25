package com.keepit.search

import com.keepit.common.akka.{SafeFuture, MonitoredAwait}
import com.keepit.common.db.{Id, ExternalId}
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.service.FortyTwoServices
import com.keepit.common.time._
import com.keepit.model._
import com.keepit.search.graph.bookmark.BookmarkRecord
import com.keepit.search.graph.bookmark.UserToUserEdgeSet
import com.keepit.search.article.ArticleRecord
import com.keepit.search.article.ArticleVisibility
import com.keepit.search.spellcheck.SpellCorrector
import com.keepit.search.query.HotDocSetFilter
import com.keepit.search.query.TextQuery
import com.keepit.search.query.parser.{MainQueryParser, MainQueryParserFactory}
import com.keepit.search.semantic.SemanticVector
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import org.apache.lucene.search.Query
import org.apache.lucene.search.Explanation
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.modules.statsd.api.Statsd
import scala.math._
import scala.concurrent.{Future, Promise}
import scala.concurrent.duration._
import com.keepit.search.util.Hit
import com.keepit.search.util.HitQueue
import com.keepit.search.semantic.SemanticVariance
import com.keepit.search.tracker.BrowsedURI
import com.keepit.search.tracker.ClickedURI
import com.keepit.search.tracker.ResultClickBoosts
import com.keepit.search.result.ShardSearchResult
import com.keepit.search.result.DetailedSearchHit
import com.keepit.search.result.BasicSearchHit
import com.keepit.search.result.FriendStats


class MainSearcher(
    userId: Id[User],
    queryString: String,
    lang1: Lang,
    lang2: Option[Lang],
    numHitsToReturn: Int,
    filter: SearchFilter,
    config: SearchConfig,
    articleSearcher: Searcher,
    parserFactory: MainQueryParserFactory,
    socialGraphInfo: SocialGraphInfo,
    clickBoostsFuture: Future[ResultClickBoosts],
    browsingHistoryFuture: Future[MultiHashFilter[BrowsedURI]],
    clickHistoryFuture: Future[MultiHashFilter[ClickedURI]],
    spellCorrector: SpellCorrector,
    monitoredAwait: MonitoredAwait,
    airbrake: AirbrakeNotifier)
    (implicit private val clock: Clock,
    private val fortyTwoServices: FortyTwoServices
) extends Logging {

  private[this] var parser: MainQueryParser = null
  def getParserUsed: Option[MainQueryParser] = Option(parser)

  private[this] var parsedQuery: Option[Query] = None
  def getParsedQuery: Option[Query] = parsedQuery

  private[this] val currentTime = currentDateTime.getMillis()
  private[this] val timeLogs = new SearchTimeLogs()
  private[this] val idFilter = filter.idFilter
  private[this] val isInitialSearch = idFilter.isEmpty

  // get config params
  private[this] val sharingBoostInNetwork = config.asFloat("sharingBoostInNetwork")
  private[this] val sharingBoostOutOfNetwork = config.asFloat("sharingBoostOutOfNetwork")
  private[this] val percentMatch = config.asFloat("percentMatch")
  private[this] val percentMatchForHotDocs = config.asFloat("percentMatchForHotDocs")
  private[this] val recencyBoost = config.asFloat("recencyBoost")
  private[this] val newContentBoost = config.asFloat("newContentBoost")
  private[this] val halfDecayMillis = config.asFloat("halfDecayHours") * (60.0f * 60.0f * 1000.0f) // hours to millis
  private[this] val dampingHalfDecayMine = config.asFloat("dampingHalfDecayMine")
  private[this] val dampingHalfDecayFriends = config.asFloat("dampingHalfDecayFriends")
  private[this] val dampingHalfDecayOthers = config.asFloat("dampingHalfDecayOthers")
  private[this] val svWeightMyBookMarks = config.asInt("svWeightMyBookMarks")
  private[this] val svWeightClickHistory = config.asInt("svWeightClickHistory")
  private[this] val similarity = Similarity(config.asString("similarity"))
  private[this] val minMyBookmarks = config.asInt("minMyBookmarks")
  private[this] val myBookmarkBoost = config.asFloat("myBookmarkBoost")
  private[this] val usefulPageBoost = config.asFloat("usefulPageBoost")
  private[this] val forbidEmptyFriendlyHits = config.asBoolean("forbidEmptyFriendlyHits")
  private[this] val useNonPersonalizedContextVector = config.asBoolean("useNonPersonalizedContextVector")

  // debug flags
  private[this] var noBookmarkCheck = false
  def debug(debugMode: String) {
    val debugFlags = debugMode.split(",").map(_.toLowerCase).toSet
    noBookmarkCheck = debugFlags.contains("nobookmarkcheck")
    log.info(s"debug option: $debugFlags")
  }

  // tailCutting is set to low when a non-default filter is in use
  private[this] val tailCutting = if (filter.isDefault && isInitialSearch) config.asFloat("tailCutting") else 0.0f

  // social graph info
  lazy val uriGraphSearcher = socialGraphInfo.uriGraphSearcher
  lazy val collectionSearcher = socialGraphInfo.collectionSearcher

  private[this] lazy val browsingFilter = monitoredAwait.result(browsingHistoryFuture, 40 millisecond, s"getting browsing history for user $userId", MultiHashFilter.emptyFilter[BrowsedURI])

  @inline private[this] def findSharingUsers(id: Long, friendEdgeSet: UserToUserEdgeSet ): UserToUserEdgeSet = {
    uriGraphSearcher.intersect(friendEdgeSet, uriGraphSearcher.getUriToUserEdgeSet(Id[NormalizedURI](id)))
  }

  @inline private[this] def sharingScore(sharingUsers: UserToUserEdgeSet): Float = {
    if (filter.isCustom) filter.filterFriends(sharingUsers.destIdSet).size.toFloat else sharingUsers.size.toFloat
  }

  @inline private[this] def sharingScore(sharingUsers: UserToUserEdgeSet, normalizedFriendStats: FriendStats): Float = {
    val users = if (filter.isCustom) filter.filterFriends(sharingUsers.destIdSet) else sharingUsers.destIdSet
    users.foldLeft(sharingUsers.size.toFloat){ (score, id) => score + normalizedFriendStats.score(id) } / 2.0f
  }

  private def getNonPersonalizedQueryContextVector(queryParser: MainQueryParser): SemanticVector = {
    val newSearcher = articleSearcher.withSemanticContext
    queryParser.svTerms.foreach{ t => newSearcher.addContextTerm(t)}
    newSearcher.getContextVector
  }

  def getPersonalizedSearcher(query: Query, nonPersonalizedContextVector: Option[Future[SemanticVector]]) = {
    val (personalReader, personalIdMapper) = uriGraphSearcher.openPersonalIndex(query)
    val indexReader = articleSearcher.indexReader.add(personalReader, personalIdMapper)

    PersonalizedSearcher(userId, indexReader, socialGraphInfo.mySearchUris, collectionSearcher, clickHistoryFuture, svWeightMyBookMarks, svWeightClickHistory, monitoredAwait, nonPersonalizedContextVector, useNonPersonalizedContextVector)
  }

  def searchText(maxTextHitsPerCategory: Int, promise: Option[Promise[_]] = None): (ArticleHitQueue, ArticleHitQueue, ArticleHitQueue, Option[PersonalizedSearcher]) = {
    val myHits = createQueue(maxTextHitsPerCategory)
    val friendsHits = createQueue(maxTextHitsPerCategory)
    val othersHits = createQueue(maxTextHitsPerCategory)

    var tParse = currentDateTime.getMillis()

    val hotDocs = new HotDocSetFilter()
    parser = parserFactory(lang1, lang2, config)
    parser.setPercentMatch(percentMatch)
    parser.setPercentMatchForHotDocs(percentMatchForHotDocs, hotDocs)

    parsedQuery = parser.parse(queryString, Some(collectionSearcher))
    val nonPersonalizedContextVector = if (useNonPersonalizedContextVector) Some(Future {getNonPersonalizedQueryContextVector(parser)}) else None

    timeLogs.queryParsing = currentDateTime.getMillis() - tParse
    timeLogs.phraseDetection = parser.phraseDetectionTime
    timeLogs.nlpPhraseDetection = parser.nlpPhraseDetectionTime

    val personalizedSearcher = parsedQuery.map{ articleQuery =>
      log.debug("articleQuery: %s".format(articleQuery.toString))

      val myUriEdgeAccessor = socialGraphInfo.myUriEdgeAccessor
      val mySearchUris = socialGraphInfo.mySearchUris
      val friendSearchUris = socialGraphInfo.friendSearchUris

      val tPersonalSearcher = currentDateTime.getMillis()
      val personalizedSearcher = getPersonalizedSearcher(articleQuery, nonPersonalizedContextVector)
      personalizedSearcher.setSimilarity(similarity)
      timeLogs.personalizedSearcher = currentDateTime.getMillis() - tPersonalSearcher

      val weight = personalizedSearcher.createWeight(articleQuery)

      val tClickBoosts = currentDateTime.getMillis()
      val clickBoosts = monitoredAwait.result(clickBoostsFuture, 5 seconds, s"getting clickBoosts for user Id $userId")
      timeLogs.getClickBoost = currentDateTime.getMillis() - tClickBoosts

      val tLucene = currentDateTime.getMillis()
      hotDocs.set(browsingFilter, clickBoosts)
      personalizedSearcher.doSearch(weight){ (scorer, reader) =>
        val visibility = ArticleVisibility(reader)
        val mapper = reader.getIdMapper
        var doc = scorer.nextDoc()
        while (doc != NO_MORE_DOCS) {
          val id = mapper.getId(doc)
          if (!idFilter.contains(id)) {
            val clickBoost = clickBoosts(id)
            val luceneScore = scorer.score()
            if (myUriEdgeAccessor.seek(id) && mySearchUris.contains(id)) {
              myHits.insert(id, luceneScore, clickBoost, true, !myUriEdgeAccessor.isPublic)
            } else if (friendSearchUris.contains(id)) {
              if (visibility.isVisible(doc)) friendsHits.insert(id, luceneScore, clickBoost, false, false)
            } else {
              if (visibility.isVisible(doc)) othersHits.insert(id, luceneScore, clickBoost, false, false)
            }
          }
          doc = scorer.nextDoc()
        }
      }
      timeLogs.search = currentDateTime.getMillis() - tLucene
      personalizedSearcher
    }
    (myHits, friendsHits, othersHits, personalizedSearcher)
  }

  def search(): ShardSearchResult = {
    val now = currentDateTime
    val (myHits, friendsHits, othersHits, personalizedSearcher) = searchText(maxTextHitsPerCategory = numHitsToReturn * 5)

    val tProcessHits = currentDateTime.getMillis()

    val myUriEdgeAccessor = socialGraphInfo.myUriEdgeAccessor
    val friendsUriEdgeAccessors = socialGraphInfo.friendsUriEdgeAccessors
    val relevantFriendEdgeSet = socialGraphInfo.relevantFriendEdgeSet

    val myTotal = myHits.totalHits
    val friendsTotal = friendsHits.totalHits
    var othersTotal = othersHits.totalHits

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
    val friendStats = FriendStats(relevantFriendEdgeSet.destIdSet)
    var numCollectStats = 20

    val usefulPages = browsingFilter
    if (myHits.size > 0 && filter.includeMine) {
      myHits.toRankedIterator.forall{ case (hit, rank) =>

        val h = hit.hit
        val sharingUsers = findSharingUsers(h.id, relevantFriendEdgeSet)

        if (numCollectStats > 0 && sharingUsers.size > 0) {
          val createdAt = myUriEdgeAccessor.getCreatedAt(h.id)
          sharingUsers.destIdLongSet.foreach{ f =>
            val keptTime = friendsUriEdgeAccessors(f).getCreatedAt(h.id)
            if (keptTime < createdAt) {
              friendStats.add(f, hit.score * 1.5f) // an early keeper gets more credit
            } else {
              friendStats.add(f, hit.score)
            }
          }
          numCollectStats -= 1
        }

        val score = hit.score * dampFunc(rank, dampingHalfDecayMine) // damping the scores by rank
        val recencyScoreVal = if (recencyBoost > 0.0f) recencyScore(myUriEdgeAccessor.getCreatedAt(h.id)) else 0.0f

        if (score > (threshold * (1.0f - recencyScoreVal))) {
          h.users = sharingUsers.destIdSet
          val scoring = new Scoring(hit.score, score / highScore, bookmarkScore(sharingScore(sharingUsers) + 1.0f), recencyScoreVal, usefulPages.mayContain(h.id, 2))
          val newScore = scoring.score(myBookmarkBoost, sharingBoostInNetwork, recencyBoost, usefulPageBoost)
          hits.insert(newScore, scoring, h)
          true
        } else {
          numCollectStats > 0
        }
      }
    }

    if (friendsHits.size > 0 && filter.includeFriends) {
      val queue = createQueue(numHitsToReturn - min(minMyBookmarks, hits.size))
      hits.discharge(hits.size - minMyBookmarks).foreach{ h => queue.insert(h) }

      val normalizedFriendStats = friendStats.normalize
      friendsHits.toRankedIterator.forall{ case (hit, rank) =>
        val h = hit.hit
        val sharingUsers = findSharingUsers(h.id, relevantFriendEdgeSet)

        var recencyScoreVal = 0.0f
        if (numCollectStats > 0) {
          val introducedAt = sharingUsers.destIdLongSet.foldLeft(Long.MaxValue){ (t, f) =>
            friendStats.add(f, hit.score)
            val keptTime = friendsUriEdgeAccessors(f).getCreatedAt(h.id)
            min(t, keptTime)
          }
          recencyScoreVal = if (newContentBoost > 0.0f) recencyScore(introducedAt) else 0.0f
          numCollectStats -= 1
        }

        val score = hit.score * dampFunc(rank, dampingHalfDecayFriends) // damping the scores by rank
        if (score > threshold) {
          h.users = sharingUsers.destIdSet
          val scoring = new Scoring(hit.score, score / highScore, bookmarkScore(sharingScore(sharingUsers, normalizedFriendStats)), recencyScoreVal, usefulPages.mayContain(h.id, 2))
          val newScore = scoring.score(1.0f, sharingBoostInNetwork, newContentBoost, usefulPageBoost)
          queue.insert(newScore, scoring, h)
          true
        } else {
          numCollectStats > 0
        }
      }
      queue.foreach{ h => hits.insert(h) }
    }

    val noFriendlyHits = (hits.size == 0)

    if (hits.size < numHitsToReturn && othersHits.size > 0 && filter.includeOthers &&
        (!forbidEmptyFriendlyHits || hits.size == 0 || !filter.isDefault || !isInitialSearch)) {
      val queue = createQueue(numHitsToReturn - hits.size)
      var othersThreshold = Float.NaN
      var othersNorm = Float.NaN
      var rank = 0 // compute the rank on the fly (there may be hits not kept public)
      othersHits.toSortedList.forall{ hit =>
        if (rank == 0) {
          // this may be the first hit from others. (re)compute the threshold and the norm.
          othersThreshold = hit.score * tailCutting
          othersNorm = max(highScore, othersHighScore)
        }
        val h = hit.hit
        val score = hit.score * dampFunc(rank, dampingHalfDecayOthers) // damping the scores by rank
        if (score > othersThreshold) {
          h.bookmarkCount = getPublicBookmarkCount(h.id)
          if (h.bookmarkCount > 0 || noBookmarkCheck) {
            val scoring = new Scoring(hit.score, score / othersNorm, bookmarkScore(h.bookmarkCount.toFloat), 0.0f, usefulPages.mayContain(h.id, 2))
            val newScore = scoring.score(1.0f, sharingBoostOutOfNetwork, 0.0f, usefulPageBoost)
            queue.insert(newScore, scoring, h)
            rank += 1
          } else {
            // no one publicly kept this page.
            // we don't include this in the result to avoid a security/privacy issue caused by a user mistake that
            // he kept a sensitive page by mistake and switch it to private.
            // decrement the count.
            othersTotal -= 1
          }
          true
        } else {
          false
        }
      }
      queue.foreach{ h => hits.insert(h) }
    } else if (myTotal + friendsTotal == hits.size) {
      // make sure there is at least one public keep in others
      othersHits.toSortedList.exists{ hit =>
        val h = hit.hit
        if (hasPublicBookmarks(h.id)) {
          true
        } else {
          othersTotal -= 1
          false
        }
      }
    }

    var hitList = hits.toSortedList
    hitList.foreach{ h => if (h.hit.bookmarkCount == 0) h.hit.bookmarkCount = getPublicBookmarkCount(h.hit.id) }

    val (show, svVar) =  if (filter.isDefault && isInitialSearch && noFriendlyHits && forbidEmptyFriendlyHits) (false, -1f) else classify(hitList, personalizedSearcher)

    val shardHits = toDetailedSearchHits(hitList)
    val collections = parser.collectionIds.map(getCollectionExternalId)

    timeLogs.processHits = currentDateTime.getMillis() - tProcessHits
    timeLogs.socialGraphInfo = socialGraphInfo.socialGraphInfoTime
    timeLogs.total = currentDateTime.getMillis() - now.getMillis()
    timing()

    ShardSearchResult(shardHits, myTotal, friendsTotal, othersTotal, friendStats, collections.toSeq, svVar, show)
  }

  private[this] def toDetailedSearchHits(hitList: List[Hit[MutableArticleHit]]): List[DetailedSearchHit] = {
    hitList.map{ hit =>
      val h = hit.hit
      if (hit.score.isInfinity) {
        log.error(s"the score value is infinity textScore=${h.luceneScore} clickBoost=${h.clickBoost} scoring=${hit.scoring}")
        hit.score = Float.MaxValue
      } else if (hit.score.isNaN) {
        log.error(s"the score value is NaN textScore=${h.luceneScore} clickBoost=${h.clickBoost} scoring=${hit.scoring}")
        hit.score = -1.0f
      }

      DetailedSearchHit(
        h.id,
        h.bookmarkCount,
        toBasicSearchHit(h),
        h.isMyBookmark,
        h.users.nonEmpty, // isFriendsBookmark
        h.isPrivate,
        h.users.toSeq,
        hit.score,
        hit.scoring
      )
    }
  }

  private[this] var collectionIdCache: Map[Long, ExternalId[Collection]] = Map()

  private def getCollectionExternalId(id: Long): ExternalId[Collection] = {
    collectionIdCache.getOrElse(id, {
      val extId = collectionSearcher.getExternalId(id)
      collectionIdCache += (id -> extId)
      extId
    })
  }

  private[this] def toBasicSearchHit(h: MutableArticleHit): BasicSearchHit = {
    val uriId = Id[NormalizedURI](h.id)
    if (h.isMyBookmark) {
      val collections = {
        val collIds = collectionSearcher.intersect(collectionSearcher.myCollectionEdgeSet, collectionSearcher.getUriToCollectionEdgeSet(uriId)).destIdLongSet
        if (collIds.isEmpty) None else Some(collIds.toSeq.sortBy(0L - _).map{ id => getCollectionExternalId(id) })
      }
      val r = getBookmarkRecord(uriId).getOrElse(throw new Exception(s"missing bookmark record: uri id = ${uriId}"))
      BasicSearchHit(Some(r.title), r.url, collections, r.externalId)
    } else {
      val r = getArticleRecord(uriId).getOrElse(throw new Exception(s"missing article record: uri id = ${uriId}"))
      BasicSearchHit(Some(r.title), r.url)
    }
  }

  private[this] def classify(hitList: List[Hit[MutableArticleHit]], personalizedSearcher: Option[PersonalizedSearcher]) = {
    def classify(scoring: Scoring, hit: MutableArticleHit, minScore: Float): Boolean = {
      hit.clickBoost > 1.1f ||
      (if (hit.isMyBookmark) scoring.recencyScore/5.0f else 0.0f) + scoring.textScore > minScore
    }

    if (filter.isDefault && isInitialSearch) {
      val textQueries = getParserUsed.map{ _.textQueries }.getOrElse(Seq.empty[TextQuery])
      val svVar = SemanticVariance.svVariance(textQueries, hitList.map(_.hit.id).toSet, personalizedSearcher) // compute sv variance. may need to record the time elapsed.

      val minScore = (0.9d - (0.7d / (1.0d + pow(svVar.toDouble/0.19d, 8.0d)))).toFloat // don't ask me how I got this formula

      // simple classifier
      val show = (parsedQuery, personalizedSearcher) match {
        case (query: Some[Query], searcher: Some[PersonalizedSearcher]) => hitList.take(5).exists{ h => classify(h.scoring, h.hit, minScore) }
        case _ => true
      }
      (show, svVar)
    } else {
      (true, -1f)
    }
  }

  @inline private[this] def getPublicBookmarkCount(id: Long) = uriGraphSearcher.getUriToUserEdgeSet(Id[NormalizedURI](id)).size
  @inline private[this] def hasPublicBookmarks(id: Long) = !(uriGraphSearcher.getUriToUserEdgeSet(Id[NormalizedURI](id)).isEmpty)

  @inline private[this] def createQueue(sz: Int) = new ArticleHitQueue(sz)

  @inline private[this] def dampFunc(rank: Int, halfDecay: Double) = (1.0d / (1.0d + pow(rank.toDouble/halfDecay, 3.0d))).toFloat

  @inline private[this] def bookmarkScore(bookmarkCount: Float) = (1.0f - (1.0f/(1.0f + bookmarkCount)))

  @inline private[this] def recencyScore(createdAt: Long): Float = {
    val t = max(currentTime - createdAt, 0).toFloat / halfDecayMillis
    val t2 = t * t
    (1.0f/(1.0f + t2))
  }

  def explain(uriId: Id[NormalizedURI]): Option[(Query, Explanation)] = {
    val hotDocs = new HotDocSetFilter()
    parser = parserFactory(lang1, lang2, config)
    parser.setPercentMatch(percentMatch)
    parser.setPercentMatchForHotDocs(percentMatchForHotDocs, hotDocs)

    val nonPersonalizedContextVector = if (useNonPersonalizedContextVector) Some(Future {getNonPersonalizedQueryContextVector(parser)}) else None

    parser.parse(queryString, Some(collectionSearcher)).map{ query =>
      var personalizedSearcher = getPersonalizedSearcher(query, nonPersonalizedContextVector)
      personalizedSearcher.setSimilarity(similarity)
      val clickBoosts = monitoredAwait.result(clickBoostsFuture, 5 seconds, s"getting clickBoosts for user Id $userId")
      hotDocs.set(browsingFilter, clickBoosts)

      (query, personalizedSearcher.explain(query, uriId.id))
    }
  }

  def getArticleRecord(uriId: Id[NormalizedURI]): Option[ArticleRecord] = {
    import com.keepit.search.article.ArticleRecordSerializer._
    articleSearcher.getDecodedDocValue[ArticleRecord]("rec", uriId.id)
  }

  def getBookmarkRecord(uriId: Id[NormalizedURI]): Option[BookmarkRecord] = uriGraphSearcher.getBookmarkRecord(uriId)
  def getBookmarkId(uriId: Id[NormalizedURI]): Long = socialGraphInfo.myUriEdgeAccessor.getBookmarkId(uriId.id)

  def timing(): Unit = {
    SafeFuture {
      timeLogs.send()

      val timeLimit = 1000L
      // search is a little slow after service restart. allow some grace period
      if (timeLogs.total > timeLimit && currentTime - fortyTwoServices.started.getMillis() > 1000*60*8) {
        val msg = s"search time exceeds limit! Limit time = $timeLimit, main-search-details: ${timeLogs.toString}."
        airbrake.notify(msg)
      }
    }
  }
}

class ArticleHitQueue(sz: Int) extends HitQueue[MutableArticleHit](sz) {

  private[this] val NO_FRIEND_IDS = Set.empty[Id[User]]

  override def lessThan(a: Hit[MutableArticleHit], b: Hit[MutableArticleHit]) = (a.score < b.score || (a.score == b.score && a.hit.id < b.hit.id))

  def insert(id: Long, textScore: Float, clickBoost: Float, isMyBookmark: Boolean, isPrivate: Boolean, friends: Set[Id[User]] = NO_FRIEND_IDS) {
    if (overflow == null) {
      insert(textScore * clickBoost, null, new MutableArticleHit(id, textScore, clickBoost, isMyBookmark, isPrivate, friends, 0))
    }
    else {
      insert(textScore * clickBoost, null, overflow.hit(id, textScore, clickBoost, isMyBookmark, isPrivate, friends, 0))
    }
  }
}

// mutable hit object for efficiency
class MutableArticleHit(
  var id: Long,
  var luceneScore: Float,
  var clickBoost: Float,
  var isMyBookmark: Boolean,
  var isPrivate: Boolean,
  var users: Set[Id[User]],
  var bookmarkCount: Int
) {
  def apply(
    newId: Long,
    newLuceneScore: Float,
    newClickBoost: Float,
    newIsMyBookmark: Boolean,
    newIsPrivate: Boolean,
    newUsers: Set[Id[User]],
    newBookmarkCount: Int
  ): MutableArticleHit = {
    id = newId
    luceneScore = newLuceneScore
    clickBoost = newClickBoost
    isMyBookmark = newIsMyBookmark
    isPrivate = newIsPrivate
    users = newUsers
    bookmarkCount = newBookmarkCount
    this
  }
}

class SearchTimeLogs(
    var socialGraphInfo: Long = 0,
    var getClickBoost: Long = 0,
    var queryParsing: Long = 0,
    var phraseDetection: Long = 0,
    var nlpPhraseDetection: Long = 0,
    var personalizedSearcher: Long = 0,
    var search: Long = 0,
    var processHits: Long = 0,
    var total: Long = 0
) {
  def send(): Unit = {
    Statsd.timing("mainSearch.socialGraphInfo", socialGraphInfo)
    Statsd.timing("mainSearch.queryParsing", queryParsing)
    Statsd.timing("mainSearch.phraseDetection", phraseDetection)
    Statsd.timing("mainSearch.nlpPhraseDetection", nlpPhraseDetection)
    Statsd.timing("mainSearch.getClickboost", getClickBoost)
    Statsd.timing("mainSearch.personalizedSearcher", personalizedSearcher)
    Statsd.timing("mainSearch.LuceneSearch", search)
    Statsd.timing("mainSearch.processHits", processHits)
    Statsd.timing("mainSearch.total", total)
  }

  override def toString() = {
    s"search time summary: total = $total, approx sum of: socialGraphInfo = $socialGraphInfo, getClickBoost = $getClickBoost, queryParsing = $queryParsing, " +
      s"personalizedSearcher = $personalizedSearcher, search = $search, processHits = $processHits"
  }
}
