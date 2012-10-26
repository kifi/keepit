package com.keepit.search

import com.keepit.search.graph.URIGraph
import com.keepit.search.index.ArticleIndexer
import com.keepit.common.db.Id
import com.keepit.model._
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import org.apache.lucene.util.PriorityQueue
import scala.math._

class MainSearcher(articleIndexer: ArticleIndexer, uriGraph: URIGraph, config: SearchConfig) {
  // get config params
  val minMyBookmarks = config.asInt("minMyBookmarks")
  val maxTextHitsPerCategory = config.asInt("maxTextHitsPerCategory")
  val myBookmarkBoost = config.asFloat("myBookmarkBoost")
  val sharingBoost = config.asFloat("sharingBoost")
    
  // get searchers. subsequent operations should use these for consistency since indexing may refresh them
  val articleSearcher = articleIndexer.getArticleSearcher
  val uriGraphSearcher = uriGraph.getURIGraphSearcher
  val NO_FRIEND_IDS = Set.empty[Id[User]]
  
  def searchText(queryString: String, userId: Id[User], friends: Set[Id[User]], filterOut: Set[Long], maxTextHitsPerCategory: Int) = {
  
    val myUris = uriGraphSearcher.getUserToUriEdgeSet(userId, publicOnly = false).destIdLongSet
    val myPublicUris = uriGraphSearcher.getUserToUriEdgeSet(userId, publicOnly = true).destIdLongSet
    val friendlyUris = friends.foldLeft(myUris){ (s, f) => s ++ uriGraphSearcher.getUserToUriEdgeSet(f, publicOnly = true).destIdLongSet }
    
    val mapper = articleSearcher.idMapper
    val myHits = createQueue(maxTextHitsPerCategory)
    val friendsHits = createQueue(maxTextHitsPerCategory)
    val othersHits = createQueue(maxTextHitsPerCategory)
    
    articleIndexer.parse(queryString).map{ articleQuery =>
      articleSearcher.doSearch(articleQuery){ scorer =>
        var doc = scorer.nextDoc()
        while (doc != NO_MORE_DOCS) {
          val id = mapper.getId(doc)
          if (!filterOut.contains(id)) {
            val score = scorer.score()
            if (friendlyUris.contains(id)) {
              if (myUris.contains(id)) {
                myHits.insert(id, score, true, myPublicUris.contains(id), NO_FRIEND_IDS, 0)
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
    (myHits, friendsHits, othersHits)
  }
  
  def search(queryString: String, userId: Id[User], friendIds: Set[Id[User]], filterOut: Set[Long], numHitsToReturn: Int): ArticleSearchResult = {
    
    val (myHits, friendsHits, othersHits) = searchText(queryString, userId, friendIds, filterOut, maxTextHitsPerCategory = maxTextHitsPerCategory)
    
    val highScore = max(max(myHits.highScore, friendsHits.highScore), othersHits.highScore)
    val myTotal = myHits.totalHits
    val friendsTotal = friendsHits.totalHits
    
    val friendEdgeSet = uriGraphSearcher.getUserToUserEdgeSet(userId, friendIds ++ Set(userId))
    
    val hits = createQueue(numHitsToReturn)
    
    myHits.foreach{ h =>
      val id = Id[NormalizedURI](h.id)
      val sharingUsers = uriGraphSearcher.intersect(friendEdgeSet, uriGraphSearcher.getUriToUserEdgeSet(id)).destIdSet - userId
      h.users = sharingUsers
      h.score = score(textScore = h.score / highScore,
                      textBoost = myBookmarkBoost,
                      bookmarkCount = (sharingUsers.size + 1),
                      bookmarkBoost = sharingBoost)
      hits.insert(h)
    }
    
    if (friendsHits.size > 0) {
      val queue = createQueue(numHitsToReturn - min(minMyBookmarks, hits.size))
      hits.drop(hits.size - minMyBookmarks).foreach{ h => queue.insert(h) }
      
      friendsHits.foreach{ h =>
        val id = Id[NormalizedURI](h.id)
        val sharingUsers = uriGraphSearcher.intersect(friendEdgeSet, uriGraphSearcher.getUriToUserEdgeSet(id)).destIdSet
        h.users = sharingUsers
        h.score = score(textScore = h.score / highScore,
                        textBoost = 1.0f,
                        bookmarkCount = sharingUsers.size,
                        bookmarkBoost= sharingBoost)
        queue.insert(h)
      }
      queue.foreach{ h => hits.insert(h) }
    }
    
    // if we don't have enough hits, backfill the result with hits in the "others" category
    val moreOthers = if (hits.size < numHitsToReturn && othersHits.size > 0) {
      // reorder by boosting
      val queue = createQueue(numHitsToReturn - hits.size)
      othersHits.foreach{ h =>
        h.bookmarkCount = getPublicBookmarkCount(h.id) // TODO: revisit this later. We probably want the private count.
        if (h.bookmarkCount > 0) {
          h.score = score(textScore = h.score / highScore,
                          textBoost = 1.0f,
                          bookmarkCount = h.bookmarkCount,
                          bookmarkBoost = sharingBoost)
          queue.insert(h)
        }
      }
      // append others
      val lowest = hits.top()
      val currentLowestScore = if (lowest != null) lowest.score - 0.01f else 1.0f
      val normalizer = currentLowestScore / queue.highScore
      queue.foreach{ h =>
        h.score = h.score * normalizer
        hits.insert(h)
      }
      queue.overflowed
    } else {
      othersHits.size > 0 // we may have more hits
    }
    
    val mayHaveMore = hits.overflowed || moreOthers // true if we _may_ have more hits
    
    ArticleSearchResult(hits.toList.map(_.toArticleHit), myTotal, friendsTotal, mayHaveMore)
  }
  
  private def getPublicBookmarkCount(id: Long) = {
    uriGraphSearcher.getUriToUserEdgeSet(Id[NormalizedURI](id)).size
  }
  
  def createQueue(sz: Int) = new ArticleHitQueue(sz)
  
  private def score(textScore: Float, textBoost: Float, bookmarkCount: Int, bookmarkBoost: Float) = {
    textScore * textBoost + (1.0f - (1.0f/(bookmarkCount.toFloat))) * bookmarkBoost
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
    if (overflow == null) overflow = new MutableArticleHit(id, score, isMyBookmark, isPrivate, friends, bookmarkCount)
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
  def toList: List[MutableArticleHit] = {
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
case class ArticleSearchResult(hits: Seq[ArticleHit], myTotal: Int, friendsTotal: Int, mayHaveMoreHits: Boolean)

// mutable hit object for efficiency
class MutableArticleHit(var id: Long, var score: Float, var isMyBookmark: Boolean, var isPrivate: Boolean, var users: Set[Id[User]], var bookmarkCount: Int) {
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
