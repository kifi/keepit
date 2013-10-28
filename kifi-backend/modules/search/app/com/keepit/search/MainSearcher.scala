package com.keepit.search

import com.keepit.search.graph.BookmarkRecord
import com.keepit.search.graph.EdgeAccessor
import com.keepit.search.graph.CollectionSearcherWithUser
import com.keepit.search.graph.URIGraphSearcherWithUser
import com.keepit.search.graph.UserToUriEdgeSet
import com.keepit.search.graph.UserToUserEdgeSet
import com.keepit.search.index.ArticleRecord
import com.keepit.search.index.ArticleVisibility
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
import com.keepit.search.query.HotDocSetFilter
import com.keepit.search.query.QueryUtil
import com.keepit.search.query.parser.SpellCorrector
import com.keepit.common.time._
import com.keepit.common.service.FortyTwoServices
import play.api.libs.json._
import java.util.UUID
import scala.math._
import org.joda.time.DateTime
import com.keepit.search.query.LuceneExplanationExtractor
import com.keepit.search.query.LuceneScoreNames
import com.keepit.shoebox.ShoeboxServiceClient
import scala.concurrent.duration._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.concurrent.Future
import scala.concurrent.future
import com.keepit.common.akka.MonitoredAwait
import play.modules.statsd.api.Statsd


class MainSearcher(
    userId: Id[User],
    queryString: String,
    langProbabilities: Map[Lang, Double],
    numHitsToReturn: Int,
    filter: SearchFilter,
    config: SearchConfig,
    lastUUID: Option[ExternalId[ArticleSearchResult]],
    articleSearcher: Searcher,
    parserFactory: MainQueryParserFactory,
    socialGraphInfoFuture: Future[SocialGraphInfo],
    clickBoostsFuture: Future[ResultClickBoosts],
    browsingHistoryFuture: Future[MultiHashFilter[BrowsedURI]],
    clickHistoryFuture: Future[MultiHashFilter[ClickedURI]],
    shoeboxClient: ShoeboxServiceClient,
    spellCorrector: SpellCorrector,
    monitoredAwait: MonitoredAwait)
    (implicit private val clock: Clock,
    private val fortyTwoServices: FortyTwoServices
) extends Logging {

  private[this] var parsedQuery: Option[Query] = None
  def getParsedQuery: Option[Query] = parsedQuery

  private[this] var lang: Lang = Lang("en")
  def getLang: Lang = lang

  private[this] val currentTime = currentDateTime.getMillis()
  private[this] val timeLogs = new MutableSearchTimeLogs()
  private[this] val idFilter = filter.idFilter
  private[this] val isInitialSearch = idFilter.isEmpty

  // get config params
  private[this] val newContentDiscoveryThreshold = config.asFloat("newContentDiscoveryThreshold")
  private[this] val sharingBoostInNetwork = config.asFloat("sharingBoostInNetwork")
  private[this] val sharingBoostOutOfNetwork = config.asFloat("sharingBoostOutOfNetwork")
  private[this] val percentMatch = config.asFloat("percentMatch")
  private[this] val percentMatchForHotDocs = config.asFloat("percentMatchForHotDocs")
  private[this] val recencyBoost = config.asFloat("recencyBoost")
  private[this] val newContentBoost = config.asFloat("newContentBoost")
  private[this] val halfDecayMillis = config.asFloat("halfDecayHours") * (60.0f * 60.0f * 1000.0f) // hours to millis
  private[this] val proximityBoost = config.asFloat("proximityBoost")
  private[this] val semanticBoost = config.asFloat("semanticBoost")
  private[this] val dampingHalfDecayMine = config.asFloat("dampingHalfDecayMine")
  private[this] val dampingHalfDecayFriends = config.asFloat("dampingHalfDecayFriends")
  private[this] val dampingHalfDecayOthers = config.asFloat("dampingHalfDecayOthers")
  private[this] val svWeightMyBookMarks = config.asInt("svWeightMyBookMarks")
  private[this] val svWeightBrowsingHistory = config.asInt("svWeightBrowsingHistory")
  private[this] val svWeightClickHistory = config.asInt("svWeightClickHistory")
  private[this] val similarity = Similarity(config.asString("similarity"))
  private[this] val phraseBoost = config.asFloat("phraseBoost")
  private[this] val siteBoost = config.asFloat("siteBoost")
  private[this] val concatBoost = config.asFloat("concatBoost")
  private[this] val minMyBookmarks = config.asInt("minMyBookmarks")
  private[this] val myBookmarkBoost = config.asFloat("myBookmarkBoost")

  // tailCutting is set to low when a non-default filter is in use
  private[this] val tailCutting = if (filter.isDefault && isInitialSearch) config.asFloat("tailCutting") else 0.001f

  // social graph info
  private[this] lazy val socialGraphInfo = monitoredAwait.result(socialGraphInfoFuture, 5 seconds, s"getting SocialGraphInfo for user Id $userId")
  lazy val uriGraphSearcher = socialGraphInfo.uriGraphSearcher
  lazy val collectionSearcher = socialGraphInfo.collectionSearcher

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

  def getPersonalizedSearcher(query: Query) = {
    val (personalReader, personalIdMapper) = uriGraphSearcher.openPersonalIndex(query)
    val indexReader = articleSearcher.indexReader.add(personalReader, personalIdMapper)

    PersonalizedSearcher(userId, indexReader, socialGraphInfo.myUris, collectionSearcher, browsingHistoryFuture, clickHistoryFuture, svWeightMyBookMarks, svWeightBrowsingHistory, svWeightClickHistory, shoeboxClient, monitoredAwait)
  }

  def searchText(maxTextHitsPerCategory: Int) = {
    val myHits = createQueue(maxTextHitsPerCategory)
    val friendsHits = createQueue(maxTextHitsPerCategory)
    val othersHits = createQueue(maxTextHitsPerCategory)

    var tParse = currentDateTime.getMillis()

    // TODO: use user profile info as a bias
    lang = LangDetector.detectShortText(queryString, langProbabilities)

    val hotDocs = new HotDocSetFilter()
    val parser = parserFactory(lang, proximityBoost, semanticBoost, phraseBoost, siteBoost, concatBoost)
    parser.setPercentMatch(percentMatch)
    parser.setPercentMatchForHotDocs(percentMatchForHotDocs, hotDocs)

    parsedQuery = parser.parse(queryString)

    timeLogs.queryParsing = currentDateTime.getMillis() - tParse
    timeLogs.phraseDetection = parser.phraseDetectionTime
    timeLogs.nlpPhraseDetection = parser.nlpPhraseDetectionTime

    val personalizedSearcher = parsedQuery.map{ articleQuery =>
      log.debug("articleQuery: %s".format(articleQuery.toString))

      val myUriEdgeAccessor = socialGraphInfo.myUriEdgeAccessor
      val friendlyUris = socialGraphInfo.friendlyUris

      val namedQueryContext = parser.namedQueryContext
      val semanticVectorScoreAccessor = namedQueryContext.getScoreAccessor("semantic vector")

      val tClickBoosts = currentDateTime.getMillis()
      val clickBoosts = monitoredAwait.result(clickBoostsFuture, 5 seconds, s"getting clickBoosts for user Id $userId")
      timeLogs.getClickBoost = currentDateTime.getMillis() - tClickBoosts

      val tPersonalSearcher = currentDateTime.getMillis()
      val personalizedSearcher = getPersonalizedSearcher(articleQuery)
      personalizedSearcher.setSimilarity(similarity)
      timeLogs.personalizedSearcher = currentDateTime.getMillis() - tPersonalSearcher
      hotDocs.set(personalizedSearcher.browsingFilter, clickBoosts)

      val tLucene = currentDateTime.getMillis()
      personalizedSearcher.doSearch(articleQuery){ (scorer, reader) =>
        val visibility = new ArticleVisibility(reader)
        val mapper = reader.getIdMapper
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
                if (visibility.isVisible(doc)) friendsHits.insert(id, score * clickBoost, score, newSemanticScore, clickBoost, false, false)
              }
            } else if (filter.includeOthers) {
              if (visibility.isVisible(doc)) othersHits.insert(id, score * clickBoost, score, newSemanticScore, clickBoost, false, false)
            }
          }
          doc = scorer.nextDoc()
        }
        namedQueryContext.reset()
      }
      timeLogs.search = currentDateTime.getMillis() - tLucene
      personalizedSearcher
    }
    (myHits, friendsHits, othersHits, personalizedSearcher)
  }

  def search(): ArticleSearchResult = {
    val now = currentDateTime
    val (myHits, friendsHits, othersHits, personalizedSearcher) = searchText(maxTextHitsPerCategory = numHitsToReturn * 5)

    val tProcessHits = currentDateTime.getMillis()

    val myUriEdgeAccessor = socialGraphInfo.myUriEdgeAccessor
    val friendsUriEdgeAccessors = socialGraphInfo.friendsUriEdgeAccessors
    val relevantFriendEdgeSet = socialGraphInfo.relevantFriendEdgeSet

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
    val friendStats = FriendStats(relevantFriendEdgeSet.destIdSet)
    var numCollectStats = 20

    if (myHits.size > 0) {
      myHits.toRankedIterator.forall{ case (h, rank) =>

        val sharingUsers = findSharingUsers(h.id, relevantFriendEdgeSet)

        if (numCollectStats > 0 && sharingUsers.size > 0) {
          val createdAt = myUriEdgeAccessor.getCreatedAt(h.id)
          sharingUsers.destIdLongSet.foreach{ f =>
            val keptTime = friendsUriEdgeAccessors(f).getCreatedAt(h.id)
            if (keptTime < createdAt) {
              friendStats.add(f, h.score * 1.5f) // an early keeper gets more credit
            } else {
              friendStats.add(f, h.score)
            }
          }
          numCollectStats -= 1
        }

        val score = h.score * dampFunc(rank, dampingHalfDecayMine) // damping the scores by rank
        val recencyScoreVal = if (recencyBoost > 0.0f) recencyScore(myUriEdgeAccessor.getCreatedAt(h.id)) else 0.0f

        if (score > (threshold * (1.0f - recencyScoreVal))) {
          h.users = sharingUsers.destIdSet
          h.scoring = new Scoring(score, score / highScore, bookmarkScore(sharingScore(sharingUsers) + 1.0f), recencyScoreVal)
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
        val sharingUsers = findSharingUsers(h.id, relevantFriendEdgeSet)

        var recencyScoreVal = 0.0f
        if (numCollectStats > 0) {
          val introducedAt = sharingUsers.destIdLongSet.foldLeft(Long.MaxValue){ (t, f) =>
            friendStats.add(f, h.score)
            val keptTime = friendsUriEdgeAccessors(f).getCreatedAt(h.id)
            min(t, keptTime)
          }
          recencyScoreVal = recencyScore(introducedAt)
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
      // if others have really high score, clear hits from mine and friends (this decision must be made after filtering out orphan URIs)
      if (queue.size > 0 && highScore < queue.highScore * tailCutting * tailCutting) hits.clear()
      queue.foreach{ h => hits.insert(h) }
    }

    var hitList = hits.toSortedList
    hitList.foreach{ h => if (h.bookmarkCount == 0) h.bookmarkCount = getPublicBookmarkCount(h.id) }

    val (svVar,svExistVar) = SemanticVariance.svVariance(parsedQuery, hitList, personalizedSearcher) // compute sv variance. may need to record the time elapsed.

    // simple classifier
    val show = (parsedQuery, personalizedSearcher) match {
      case (query: Some[Query], searcher: Some[PersonalizedSearcher]) => classify(hitList, searcher.get, svVar)
      case _ => true
    }

    // insert a new content if any (after show/no-show classification)
    newContent.foreach { h =>
      if (h.bookmarkCount == 0) h.bookmarkCount = getPublicBookmarkCount(h.id)
      hitList = (hitList.take(numHitsToReturn - 1) :+ h)
    }

    timeLogs.processHits = currentDateTime.getMillis() - tProcessHits
    timeLogs.socialGraphInfo = socialGraphInfo.socialGraphInfoTime
    timeLogs.total = currentDateTime.getMillis() - now.getMillis()

    val searchResultUuid = ExternalId[ArticleSearchResult]()

    val newIdFilter = filter.idFilter ++ hitList.map(_.id)

    ArticleSearchResult(lastUUID, queryString, hitList.map(_.toArticleHit(friendStats)),
        myTotal, friendsTotal, !hitList.isEmpty, hitList.map(_.scoring), newIdFilter, timeLogs.total.toInt,
        (idFilter.size / numHitsToReturn).toInt, uuid = searchResultUuid, svVariance = svVar, svExistenceVar = svExistVar, toShow = show,
        timeLogs = Some(timeLogs.toSearchTimeLogs),
        lang = lang)
  }

  private[this] def classify(hitList: List[MutableArticleHit], personalizedSearcher: PersonalizedSearcher, svVar: Float) = {
    val semanticScoreThreshold = (1.0f - semanticBoost) + semanticBoost * 0.2f
    def classify(hit: MutableArticleHit) = {
      (hit.clickBoost) > 1.1f ||
      hit.scoring.recencyScore > 0.25f ||
      hit.scoring.textScore > 0.7f ||
      (hit.scoring.textScore >= 0.04f && hit.semanticScore >= semanticScoreThreshold && svVar < 0.18f)
    }
    hitList.take(3).exists(classify(_))
  }

  @inline private[this] def getPublicBookmarkCount(id: Long) = uriGraphSearcher.getUriToUserEdgeSet(Id[NormalizedURI](id)).size

  @inline private[this] def createQueue(sz: Int) = new ArticleHitQueue(sz)

  @inline private[this] def dampFunc(rank: Int, halfDecay: Double) = (1.0d / (1.0d + pow(rank.toDouble/halfDecay, 3.0d))).toFloat

  @inline private[this] def bookmarkScore(bookmarkCount: Float) = (1.0f - (1.0f/(1.0f + bookmarkCount)))

  @inline private[this] def recencyScore(createdAt: Long): Float = {
    val t = max(currentTime - createdAt, 0).toFloat / halfDecayMillis
    val t2 = t * t
    (1.0f/(1.0f + t2))
  }

  def explain(uriId: Id[NormalizedURI]): Option[(Query, Explanation)] = {
    // TODO: use user profile info as a bias
    lang = LangDetector.detectShortText(queryString, langProbabilities)
    val hotDocs = new HotDocSetFilter()
    val parser = parserFactory(lang, proximityBoost, semanticBoost, phraseBoost, siteBoost, concatBoost)
    parser.setPercentMatch(percentMatch)
    parser.setPercentMatchForHotDocs(percentMatchForHotDocs, hotDocs)

    parser.parse(queryString).map{ query =>
      var personalizedSearcher = getPersonalizedSearcher(query)
      personalizedSearcher.setSimilarity(similarity)
      val clickBoosts = monitoredAwait.result(clickBoostsFuture, 5 seconds, s"getting clickBoosts for user Id $userId")
      hotDocs.set(personalizedSearcher.browsingFilter, clickBoosts)

      (query, personalizedSearcher.explain(query, uriId.id))
    }
  }

  def getArticleRecord(uriId: Id[NormalizedURI]): Option[ArticleRecord] = {
    import com.keepit.search.index.ArticleRecordSerializer._
    articleSearcher.getDecodedDocValue[ArticleRecord]("rec", uriId.id)
  }

  def getBookmarkRecord(uriId: Id[NormalizedURI]): Option[BookmarkRecord] = uriGraphSearcher.getBookmarkRecord(uriId)
  def getBookmarkId(uriId: Id[NormalizedURI]): Long = socialGraphInfo.myUriEdgeAccessor.getBookmarkId(uriId.id)

  def timing() {
    Statsd.timing("mainSearch.socialGraphInfo", timeLogs.socialGraphInfo)
    Statsd.timing("mainSearch.queryParsing", timeLogs.queryParsing)
    Statsd.timing("mainSearch.phraseDetection", timeLogs.phraseDetection)
    Statsd.timing("mainSearch.nlpPhraseDetection", timeLogs.nlpPhraseDetection)
    Statsd.timing("mainSearch.getClickboost", timeLogs.getClickBoost)
    Statsd.timing("mainSearch.personalizedSearcher", timeLogs.personalizedSearcher)
    Statsd.timing("mainSearch.LuceneSearch", timeLogs.search)
    Statsd.timing("mainSearch.processHits", timeLogs.processHits)
    Statsd.timing("mainSearch.total", timeLogs.total)

    articleSearcher.indexWarmer.foreach{ warmer =>
      parsedQuery.foreach{ query =>
          warmer.addTerms(QueryUtil.getTerms(query))
      }
    }
  }
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
    var phraseDetection: Long = 0,
    var nlpPhraseDetection: Long = 0,
    var personalizedSearcher: Long = 0,
    var search: Long = 0,
    var processHits: Long = 0,
    var total: Long = 0
) {
  def toSearchTimeLogs = SearchTimeLogs(socialGraphInfo, getClickBoost, queryParsing, personalizedSearcher, search, processHits, total)
}
