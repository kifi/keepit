package com.keepit.search.engine

import com.keepit.common.akka.{ SafeFuture, MonitoredAwait }
import com.keepit.common.db.Id
import com.keepit.common.logging.Logging
import com.keepit.common.service.FortyTwoServices
import com.keepit.common.time._
import com.keepit.model._
import com.keepit.search._
import com.keepit.search.engine.result.{ MutableKifiHit, KifiHitQueue, KifiResultCollector }
import com.keepit.search.graph.bookmark.BookmarkRecord
import com.keepit.search.graph.bookmark.UserToUserEdgeSet
import com.keepit.search.article.ArticleRecord
import com.keepit.search.graph.keep.KeepFields
import org.apache.lucene.index.Term
import org.apache.lucene.search.Query
import org.apache.lucene.search.Explanation
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.math._
import scala.concurrent.{ Future, Promise }
import scala.concurrent.duration._
import com.keepit.search.util.Hit
import com.keepit.search.tracker.ClickedURI
import com.keepit.search.tracker.ResultClickBoosts
import com.keepit.search.result.PartialSearchResult
import com.keepit.search.result.DetailedSearchHit
import com.keepit.search.result.BasicSearchHit
import com.keepit.search.result.FriendStats

class KifiSearch(
    userId: Id[User],
    lang1: Lang,
    lang2: Option[Lang],
    numHitsToReturn: Int,
    filter: SearchFilter,
    config: SearchConfig,
    engine: QueryEngine,
    articleSearcher: Searcher,
    keepSearcher: Searcher,
    socialGraphInfo: SocialGraphInfo,
    libraryIdsFuture: Future[(Seq[Long], Seq[Long], Seq[Long])],
    clickBoostsFuture: Future[ResultClickBoosts],
    clickHistoryFuture: Future[MultiHashFilter[ClickedURI]],
    monitoredAwait: MonitoredAwait,
    timeLogs: SearchTimeLogs)(implicit private val clock: Clock,
        private val fortyTwoServices: FortyTwoServices) extends Logging {

  private[this] val currentTime = currentDateTime.getMillis()
  private[this] val idFilter = filter.idFilter
  private[this] val isInitialSearch = idFilter.isEmpty

  // get config params
  private[this] val sharingBoostInNetwork = config.asFloat("sharingBoostInNetwork")
  private[this] val sharingBoostOutOfNetwork = config.asFloat("sharingBoostOutOfNetwork")
  private[this] val recencyBoost = config.asFloat("recencyBoost")
  private[this] val newContentBoost = config.asFloat("newContentBoost")
  private[this] val halfDecayMillis = config.asFloat("halfDecayHours") * (60.0f * 60.0f * 1000.0f) // hours to millis
  private[this] val dampingHalfDecayMine = config.asFloat("dampingHalfDecayMine")
  private[this] val dampingHalfDecayFriends = config.asFloat("dampingHalfDecayFriends")
  private[this] val dampingHalfDecayOthers = config.asFloat("dampingHalfDecayOthers")
  private[this] val similarity = Similarity(config.asString("similarity"))
  private[this] val minMyBookmarks = config.asInt("minMyBookmarks")
  private[this] val myBookmarkBoost = config.asFloat("myBookmarkBoost")
  private[this] val usefulPageBoost = config.asFloat("usefulPageBoost")
  private[this] val forbidEmptyFriendlyHits = config.asBoolean("forbidEmptyFriendlyHits")
  private[this] val percentMatch = config.asFloat("percentMatch")

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

  @inline private[this] def findSharingUsers(id: Long, friendEdgeSet: UserToUserEdgeSet): UserToUserEdgeSet = {
    uriGraphSearcher.intersect(friendEdgeSet, uriGraphSearcher.getUriToUserEdgeSet(Id[NormalizedURI](id)))
  }

  @inline private[this] def sharingScore(sharingUsers: UserToUserEdgeSet): Float = sharingUsers.size.toFloat

  @inline private[this] def sharingScore(sharingUsers: UserToUserEdgeSet, normalizedFriendStats: FriendStats): Float = {
    val users = sharingUsers.destIdSet
    users.foldLeft(sharingUsers.size.toFloat) { (score, id) => score + normalizedFriendStats.score(id) } / 2.0f
  }

  def searchText(maxTextHitsPerCategory: Int, promise: Option[Promise[_]] = None): (KifiHitQueue, KifiHitQueue, KifiHitQueue) = {

    val keepScoreSource = new UriFromKeepsScoreVectorSource(keepSearcher, libraryIdsFuture, filter.idFilter, monitoredAwait)
    engine.execute(keepScoreSource)

    val tPersonalSearcher = currentDateTime.getMillis()
    val personalizedSearcher = PersonalizedSearcher(articleSearcher.indexReader, socialGraphInfo.mySearchUris)
    personalizedSearcher.setSimilarity(similarity)
    timeLogs.personalizedSearcher = currentDateTime.getMillis() - tPersonalSearcher

    val articleScoreSource = new UriFromArticlesScoreVectorSource(personalizedSearcher, filter.idFilter)
    engine.execute(articleScoreSource)

    val tClickBoosts = currentDateTime.getMillis()
    val clickBoosts = monitoredAwait.result(clickBoostsFuture, 5 seconds, s"getting clickBoosts for user Id $userId")
    timeLogs.getClickBoost = currentDateTime.getMillis() - tClickBoosts

    val collector = new KifiResultCollector(clickBoosts, maxTextHitsPerCategory, percentMatch)
    engine.join(collector)

    collector.getResults()
  }

  def search(): PartialSearchResult = {
    val now = currentDateTime
    val (myHits, friendsHits, othersHits) = searchText(maxTextHitsPerCategory = numHitsToReturn * 5)

    val tProcessHits = currentDateTime.getMillis()

    val myUriEdgeAccessor = socialGraphInfo.myUriEdgeAccessor
    val friendsUriEdgeAccessors = socialGraphInfo.friendsUriEdgeAccessors
    val friendEdgeSet = socialGraphInfo.friendEdgeSet

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
    val friendStats = FriendStats(friendEdgeSet.destIdLongSet)

    val usefulPages = monitoredAwait.result(clickHistoryFuture, 40 millisecond, s"getting click history for user $userId", MultiHashFilter.emptyFilter[ClickedURI])

    if (myHits.size > 0 && filter.includeMine) {
      myHits.toRankedIterator.forall {
        case (hit, rank) =>

          val h = hit.hit
          val score = hit.score * dampFunc(rank, dampingHalfDecayMine) // damping the scores by rank
          val recencyScoreVal = if (recencyBoost > 0.0f) recencyScore(myUriEdgeAccessor.getCreatedAt(h.id)) else 0.0f

          if (score > (threshold * (1.0f - recencyScoreVal))) {
            val scoring = new Scoring(hit.score, score / highScore, bookmarkScore(h.visibleCount.toFloat + 1.0f), recencyScoreVal, usefulPages.mayContain(h.id, 2))
            val newScore = scoring.score(myBookmarkBoost, sharingBoostInNetwork, recencyBoost, usefulPageBoost)
            hits.insert(newScore, scoring, h)
            true
          } else {
            false
          }
      }
    }

    if (friendsHits.size > 0 && filter.includeFriends) {
      val queue = createQueue(numHitsToReturn - min(minMyBookmarks, hits.size))
      hits.discharge(hits.size - minMyBookmarks).foreach { h => queue.insert(h) }

      friendsHits.toRankedIterator.forall {
        case (hit, rank) =>
          val h = hit.hit
          val sharingUsers = findSharingUsers(h.id, friendEdgeSet)
          val sharingUserSize = sharingUsers.size.toFloat

          var recencyScoreVal = 0.0f
          if (sharingUserSize > 0.0f) {
            val introducedAt = sharingUsers.destIdLongSet.foldLeft(Long.MaxValue) { (t, f) =>
              val keptTime = friendsUriEdgeAccessors(f).getCreatedAt(h.id)
              min(t, keptTime)
            }
            recencyScoreVal = if (newContentBoost > 0.0f) recencyScore(introducedAt) else 0.0f
          }

          val score = hit.score * dampFunc(rank, dampingHalfDecayFriends) // damping the scores by rank
          if (score > threshold) {
            val scoring = new Scoring(hit.score, score / highScore, bookmarkScore(h.visibleCount.toFloat + 1.0f), recencyScoreVal, usefulPages.mayContain(h.id, 2))
            val newScore = scoring.score(1.0f, sharingBoostInNetwork, newContentBoost, usefulPageBoost)
            queue.insert(newScore, scoring, h)
            true
          } else {
            false
          }
      }
      queue.foreach { h => hits.insert(h) }
    }

    val noFriendlyHits = (hits.size == 0)

    if (hits.size < numHitsToReturn && othersHits.size > 0 && filter.includeOthers &&
      (!forbidEmptyFriendlyHits || hits.size == 0 || !filter.isDefault || !isInitialSearch)) {
      val queue = createQueue(numHitsToReturn - hits.size)
      var othersThreshold = Float.NaN
      var othersNorm = Float.NaN
      var rank = 0 // compute the rank on the fly (there may be hits not kept public)
      othersHits.toSortedList.forall { hit =>
        if (rank == 0) {
          // this may be the first hit from others. (re)compute the threshold and the norm.
          othersThreshold = hit.score * tailCutting
          othersNorm = max(highScore, othersHighScore)
        }
        val h = hit.hit
        val score = hit.score * dampFunc(rank, dampingHalfDecayOthers) // damping the scores by rank
        if (score > othersThreshold) {
          h.visibleCount = getPublicBookmarkCount(h.id)
          if (h.visibleCount > 0 || noBookmarkCheck) {
            val scoring = new Scoring(hit.score, score / othersNorm, bookmarkScore(h.visibleCount.toFloat), 0.0f, usefulPages.mayContain(h.id, 2))
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
      queue.foreach { h => hits.insert(h) }
    }

    var hitList = hits.toSortedList
    hitList.foreach { h => if (h.hit.visibleCount == 0) h.hit.visibleCount = getPublicBookmarkCount(h.hit.id) }

    val show = if (filter.isDefault && isInitialSearch && noFriendlyHits && forbidEmptyFriendlyHits) false else classify(hitList)

    val shardHits = toDetailedSearchHits(hitList)

    timeLogs.processHits = currentDateTime.getMillis() - tProcessHits
    timeLogs.socialGraphInfo = socialGraphInfo.socialGraphInfoTime
    timeLogs.total = currentDateTime.getMillis() - now.getMillis()
    timing()

    PartialSearchResult(shardHits, myTotal, friendsTotal, othersTotal, friendStats, -1, show)
  }

  private[this] def toDetailedSearchHits(hitList: List[Hit[MutableKifiHit]]): List[DetailedSearchHit] = {
    val myUriEdgeAccessor = socialGraphInfo.myUriEdgeAccessor

    hitList.map { hit =>
      val h = hit.hit
      if (hit.score.isInfinity) {
        log.error(s"the score value is infinity textScore=${h.luceneScore} clickBoost=${h.clickBoost} scoring=${hit.scoring}")
        hit.score = Float.MaxValue
      } else if (hit.score.isNaN) {
        log.error(s"the score value is NaN textScore=${h.luceneScore} clickBoost=${h.clickBoost} scoring=${hit.scoring}")
        hit.score = -1.0f
      }

      val isPrivate = (h.visibility == Visibility.SECRET)

      DetailedSearchHit(
        h.id,
        h.visibleCount,
        toBasicSearchHit(h),
        h.visibility == Visibility.MEMBER,
        h.visibility == Visibility.NETWORK, // isFriendsBookmark
        h.visibility == Visibility.SECRET, // isPrivate
        Seq(), // TODO: sharing users
        hit.score,
        hit.scoring
      )
    }
  }

  private[this] def toBasicSearchHit(h: MutableKifiHit): BasicSearchHit = {
    val uriId = Id[NormalizedURI](h.id)
    if (h.visibility == Visibility.MEMBER) {
      val collections = {
        val collIds = collectionSearcher.intersect(collectionSearcher.myCollectionEdgeSet, collectionSearcher.getUriToCollectionEdgeSet(uriId)).destIdLongSet
        if (collIds.isEmpty) None else Some(collIds.toSeq.sortBy(0L - _).map { id => collectionSearcher.getExternalId(id) }.collect { case Some(extId) => extId })
      }
      val r = getBookmarkRecord(uriId).getOrElse(throw new Exception(s"missing bookmark record: uri id = ${uriId}"))
      BasicSearchHit(Some(r.title), r.url, collections, r.externalId)
    } else {
      val r = getArticleRecord(uriId).getOrElse(throw new Exception(s"missing article record: uri id = ${uriId}"))
      BasicSearchHit(Some(r.title), r.url)
    }
  }

  private[this] def classify(hitList: List[Hit[MutableKifiHit]]) = {
    def classify(scoring: Scoring, hit: MutableKifiHit, minScore: Float): Boolean = {
      hit.clickBoost > 1.1f ||
        (if (hit.visibility == Visibility.MEMBER) scoring.recencyScore / 5.0f else 0.0f) + scoring.textScore > minScore
    }

    if (filter.isDefault && isInitialSearch) {
      // simple classifier
      val show = hitList.take(5).exists { h => classify(h.scoring, h.hit, 0.6f) }
      show
    } else {
      true
    }
  }

  @inline private[this] def getPublicBookmarkCount(id: Long) = keepSearcher.count(new Term(KeepFields.discoverableUriField, id.toString))

  @inline private[this] def createQueue(sz: Int) = new KifiHitQueue(sz)

  @inline private[this] def dampFunc(rank: Int, halfDecay: Double) = (1.0d / (1.0d + pow(rank.toDouble / halfDecay, 3.0d))).toFloat

  @inline private[this] def bookmarkScore(bookmarkCount: Float) = (1.0f - (1.0f / (1.0f + bookmarkCount)))

  @inline private[this] def recencyScore(createdAt: Long): Float = {
    val t = max(currentTime - createdAt, 0).toFloat / halfDecayMillis
    val t2 = t * t
    (1.0f / (1.0f + t2))
  }

  def explain(uriId: Id[NormalizedURI]): Option[(Query, Explanation)] = {
    throw new UnsupportedOperationException("explanation is not supported yet")
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
    }
  }
}
