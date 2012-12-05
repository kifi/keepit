package com.keepit.search

import com.keepit.search.graph.URIGraph
import com.keepit.search.graph.URIList
import com.keepit.search.index.ArticleIndexer
import com.keepit.common.db.{Id, ExternalId}
import com.keepit.common.time._
import com.keepit.model._
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import org.apache.lucene.util.PriorityQueue
import java.util.UUID
import scala.math._
import org.joda.time.DateTime

class MainSearcher(userId: Id[User], friendIds: Set[Id[User]], filterOut: Set[Long], articleIndexer: ArticleIndexer, uriGraph: URIGraph, config: SearchConfig) {
  val currentTime = currentDateTime.getMillis()

  // get config params
  val minMyBookmarks = config.asInt("minMyBookmarks")
  val myBookmarkBoost = config.asFloat("myBookmarkBoost")
  val sharingBoostInNetwork = config.asFloat("sharingBoostInNetwork")
  val sharingBoostOutOfNetwork = config.asFloat("sharingBoostOutOfNetwork")
  val percentMatch = config.asFloat("percentMatch")
  val recencyBoost = config.asFloat("recencyBoost")
  val halfDecayMillis = config.asFloat("halfDecayHours") * (60.0f * 60.0f * 1000.0f) // hours to millis
  val tailCutting = config.asFloat("tailCutting")
  val proximityBoost = config.asFloat("proximityBoost")
  val semanticBoost = config.asFloat("semanticBoost")
  val dumpingByRank = config.asBoolean("dumpingByRank")
  val dumpingHalfDecayMine = config.asFloat("dumpingHalfDecayMine")
  val dumpingHalfDecayFriends = config.asFloat("dumpingHalfDecayFriends")
  val dumpingHalfDecayOthers = config.asFloat("dumpingHalfDecayOthers")

  // get searchers. subsequent operations should use these for consistency since indexing may refresh them
  val articleSearcher = articleIndexer.getArticleSearcher
  val uriGraphSearcher = uriGraph.getURIGraphSearcher
  val NO_FRIEND_IDS = Set.empty[Id[User]]

  // initialize user's social graph info
  val myUriEdges = uriGraphSearcher.getUserToUriEdgeSetWithCreatedAt(userId, publicOnly = false)
  val myUris = myUriEdges.destIdLongSet
  val myPublicUris = uriGraphSearcher.getUserToUriEdgeSet(userId, publicOnly = true).destIdLongSet
  val friendlyUris = friendIds.foldLeft(myUris){ (s, f) => s ++ uriGraphSearcher.getUserToUriEdgeSet(f, publicOnly = true).destIdLongSet }

  def searchBookmarkTitle(queryString: String)(implicit lang: Lang) = {
    val bookmarkTitleSearchParser = uriGraph.getQueryParser(lang)
    bookmarkTitleSearchParser.setPercentMatch(percentMatch)
    bookmarkTitleSearchParser.parseQuery(queryString).map{ bookmarkQuery =>
      uriGraphSearcher.search(userId, bookmarkQuery, percentMatch)
    }.getOrElse(Map.empty[Long, Float])
  }

  def searchText(queryString: String, maxTextHitsPerCategory: Int)(implicit lang: Lang) = {
    var bookmarkTitleHits = searchBookmarkTitle(queryString)

    val mapper = articleSearcher.idMapper
    val myHits = createQueue(maxTextHitsPerCategory)
    val friendsHits = createQueue(maxTextHitsPerCategory)
    val othersHits = createQueue(maxTextHitsPerCategory)

    val articleParser = articleIndexer.getQueryParser(lang, proximityBoost, semanticBoost)
    articleParser.setPercentMatch(percentMatch)
    articleParser.parseQuery(queryString).map{ articleQuery =>
      articleSearcher.doSearch(articleQuery){ scorer =>
        var doc = scorer.nextDoc()
        while (doc != NO_MORE_DOCS) {
          val id = mapper.getId(doc)
          if (!filterOut.contains(id)) {
            val score = scorer.score()
            if (friendlyUris.contains(id)) {
              if (myUris.contains(id)) {
                // blend with personal bookmark title score
                val blendedScore = score + bookmarkTitleHits.getOrElse(id, 0.0f)
                bookmarkTitleHits -= id
                myHits.insert(id, blendedScore, true, !myPublicUris.contains(id), NO_FRIEND_IDS, 0)
              } else {
                friendsHits.insert(id, score, false, false, NO_FRIEND_IDS, 0)
              }
            } else {
              othersHits.insert(id, score, false, false, NO_FRIEND_IDS, 0)
            }
          }
          doc = scorer.nextDoc()
        }
      }
    }
    bookmarkTitleHits.foreach{ case (id, score) =>
      if (!filterOut.contains(id)) myHits.insert(id, score, true, !myPublicUris.contains(id), NO_FRIEND_IDS, 0)
    }
    (myHits, friendsHits, othersHits)
  }

  def search(queryString: String, numHitsToReturn: Int, lastUUID: Option[ExternalId[ArticleSearchResultRef]]): ArticleSearchResult = {
    implicit val lang = Lang("en") // TODO: detect
    val now = currentDateTime
    val (myHits, friendsHits, othersHits) = searchText(queryString, maxTextHitsPerCategory = numHitsToReturn * 5)

    val myTotal = myHits.totalHits
    val friendsTotal = friendsHits.totalHits

    val friendEdgeSet = uriGraphSearcher.getUserToUserEdgeSet(userId, friendIds ++ Set(userId))

    val hits = createQueue(numHitsToReturn)

    // global high score
    val highScore = max(max(myHits.highScore, friendsHits.highScore), othersHits.highScore)

    var threshold = highScore * tailCutting

    myHits.toSortedList.iterator.zipWithIndex.map{ case (h, rank) =>
      if (dumpingByRank) h.score = h.score * dumpFunc(rank, dumpingHalfDecayMine) // dumping the scores by rank
      h
    }.takeWhile{ h =>
      h.score > threshold
    }.foreach{ h =>
      val id = Id[NormalizedURI](h.id)
      val sharingUsers = uriGraphSearcher.intersect(friendEdgeSet, uriGraphSearcher.getUriToUserEdgeSet(id)).destIdSet - userId

      h.users = sharingUsers
      h.scoring = new Scoring(h.score, h.score / highScore, bookmarkScore(sharingUsers.size + 1), recencyScore(myUriEdges.getCreatedAt(id)))
      h.score = h.scoring.score(myBookmarkBoost, sharingBoostInNetwork, recencyBoost)
      hits.insert(h)
    }

    if (friendsHits.size > 0) {
      val queue = createQueue(numHitsToReturn - min(minMyBookmarks, hits.size))
      hits.drop(hits.size - minMyBookmarks).foreach{ h => queue.insert(h) }
      friendsHits.toSortedList.iterator.zipWithIndex.map{ case (h, rank) =>
        if (dumpingByRank) h.score = h.score * dumpFunc(rank, dumpingHalfDecayFriends) // dumping the scores by rank
        h
      }.takeWhile{ h =>
        h.score > threshold
      }.foreach{ h =>
        val id = Id[NormalizedURI](h.id)
        val sharingUsers = uriGraphSearcher.intersect(friendEdgeSet, uriGraphSearcher.getUriToUserEdgeSet(id)).destIdSet

        h.users = sharingUsers
        h.scoring = new Scoring(h.score, h.score / highScore, bookmarkScore(sharingUsers.size), 0.0f)
        h.score = h.scoring.score(1.0f, sharingBoostInNetwork, recencyBoost)
        queue.insert(h)
      }
      queue.foreach{ h => hits.insert(h) }
    }

    if (hits.size < numHitsToReturn && othersHits.size > 0) {
      val queue = createQueue(numHitsToReturn - hits.size)
      othersHits.toSortedList.iterator.zipWithIndex.map{ case (h, rank) =>
        if (dumpingByRank) h.score = h.score * dumpFunc(rank, dumpingHalfDecayOthers) // dumping the scores by rank
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

    val newFilter = filterOut ++ hitList.map(_.id)
    val millisPassed = currentDateTime.getMillis() - now.getMillis()
    ArticleSearchResult(lastUUID, queryString, hitList.map(_.toArticleHit),
        myTotal, friendsTotal, !hitList.isEmpty, hitList.map(_.scoring), newFilter, millisPassed.toInt, (filterOut.size / numHitsToReturn).toInt)
  }

  private def getPublicBookmarkCount(id: Long) = {
    uriGraphSearcher.getUriToUserEdgeSet(Id[NormalizedURI](id)).size
  }

  def createQueue(sz: Int) = new ArticleHitQueue(sz)

  private def dumpFunc(rank: Int, halfDecay: Double) = (1.0d / (1.0d + pow(rank.toDouble/2.0d, 3.0d))).toFloat

  private def bookmarkScore(bookmarkCount: Int) = (1.0f - (1.0f/(bookmarkCount.toFloat)))

  private def recencyScore(createdAt: Long): Float = {
    val t = max(currentTime - createdAt, 0).toFloat / halfDecayMillis
    val t2 = t * t
    (1.0f/(1.0f + t2))
  }
}

class ArticleHitQueue(sz: Int) extends PriorityQueue[MutableArticleHit] {

  super.initialize(sz)

  var highScore = Float.MinValue
  var totalHits = 0

  override def lessThan(a: MutableArticleHit, b: MutableArticleHit) = (a.score < b.score || (a.score == b.score && a.id < b.id))

  var overflow: MutableArticleHit = null // sorry about the null, but this is necessary to work with lucene's priority queue efficiently

  def overflowed = (overflow != null)

  def insert(id: Long, score: Float, isMyBookmark: Boolean, isPrivate: Boolean, friends: Set[Id[User]], bookmarkCount: Int) {
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

  def addAll(otherQueue: ArticleHitQueue) {
    val thisQueue = this
    otherQueue.foreach{ h => thisQueue.insert(h) }
  }

  def iterator = {
    val arr = getHeapArray()
    val sz = size()

    new Iterator[MutableArticleHit] {
      var i = 0
      def hasNext() = (i < sz)
      def next() = {
        i += 1
        arr(i).asInstanceOf[MutableArticleHit]
      }
    }
  }

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
  time: DateTime = currentDateTime)

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
