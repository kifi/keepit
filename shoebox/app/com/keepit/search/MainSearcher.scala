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
  val maxMyBookmarks = config.asInt("maxMyBookmarks")
  val maxTextHitsPerCategory = config.asInt("maxTextHitsPerCategory")
  val myBookmarkBoost = config.asFloat("myBookmarkBoost")
  val sharingBoost = config.asFloat("sharingBoost")
  val othersBookmarkWeight = config.asFloat("othersBookmarkWeight")
    
  // get searchers. subsequent operations should use these for consistency since indexing may refresh them
  val articleSearcher = articleIndexer.getArticleSearcher
  val uriGraphSearcher = uriGraph.getURIGraphSearcher
  val NO_FRIEND_IDS = Set.empty[Id[User]]
  
  def searchText(queryString: String, userId: Id[User], friends: Set[Id[User]], filterOut: Set[Long], maxTextHitsPerCategory: Int) = {
  
    val myUris = uriGraphSearcher.getUserToUriEdgeSet(userId, publicOnly = false).destIdLongSet
    val myPublicUris = uriGraphSearcher.getUserToUriEdgeSet(userId, publicOnly = true).destIdLongSet
    val friendlyUris = myUris ++ friends.flatMap(uriGraphSearcher.getUserToUriEdgeSet(_, publicOnly = true).destIdLongSet)
    
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
                myHits.insert(id, true, myPublicUris.contains(id), score, NO_FRIEND_IDS)                
              } else {
                friendsHits.insert(id, false, false, score, NO_FRIEND_IDS)
              }
            } else {
              othersHits.insert(id, false, false, score, NO_FRIEND_IDS)
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
    val totalHits = myHits.totalHits + friendsHits.totalHits + othersHits.totalHits
    
    val friendEdgeSet = uriGraphSearcher.getUserToUserEdgeSet(userId, friendIds ++ Set(userId))
    
    val hits = createQueue(numHitsToReturn)
    
    myHits.foreach{ h =>
      val id = Id[NormalizedURI](h.id)
      val sharingUsers = uriGraphSearcher.intersect(friendEdgeSet, uriGraphSearcher.getUriToUserEdgeSet(id)).destIdSet - userId
      h.users = sharingUsers
      h.score = score(h.score / myHits.highScore, myBookmarkBoost, sharingUsers.size.toFloat, sharingBoost)
      hits.insert(h)
    }
    
    if (hits.size > maxMyBookmarks) {
      hits.drop(hits.size - maxMyBookmarks)
    }
    
    if (hits.size < numHitsToReturn) {
      friendsHits.foreach{ h =>
        val id = Id[NormalizedURI](h.id)
        val sharingUsers = uriGraphSearcher.intersect(friendEdgeSet, uriGraphSearcher.getUriToUserEdgeSet(id)).destIdSet
        h.users = sharingUsers
        h.score = score(h.score / friendsHits.highScore, 1.0f, sharingUsers.size.toFloat, sharingBoost)
        hits.insert(h)
      }
    }
    
    if(hits.size < numHitsToReturn) {
      // backfill the result with hits in the "others" category 
      othersHits.drop(othersHits.size - (numHitsToReturn - hits.size))
      othersHits.foreach{ h =>
        h.score = score(h.score / othersHits.highScore, 1.0f, othersBookmarkWeight, sharingBoost)
        hits.insert(h)
      }
    }
    
    ArticleSearchResult(hits.toList.map(_.toArticleHit), totalHits - hits.size)
  }
  
  def createQueue(sz: Int) = new ArticleHitQueue(sz)
  
  private def score(textScore: Float, textBoost: Float, bookmarkCount: Float, bookmarkBoost: Float) = {
    textScore * textBoost + (1.0f - (1.0f/(bookmarkCount))) * bookmarkBoost
  }
}

class ArticleHitQueue(sz: Int) extends PriorityQueue[MutableArticleHit] {
  initialize(sz)
  
  var highScore = -1.0f
  var totalHits = 0
  
  override def lessThan(a: MutableArticleHit, b: MutableArticleHit) = (a.score < b.score || (a.score == b.score && a.id < b.id))

  var overflow: MutableArticleHit = null // sorry about the null, but this is necessary to work with lucene's priority queue efficiently
  
  def insert(id: Long, isMyBookmark: Boolean, isPrivate: Boolean, score: Float, friends: Set[Id[User]]) {
    if (overflow == null) overflow = new MutableArticleHit(id, isMyBookmark, isPrivate, score, friends)
    else overflow(id, isMyBookmark, isPrivate, score, friends)
    
    if (score > highScore) highScore = score
    
    overflow = insertWithOverflow(overflow)
    totalHits += 1
  }
  
  def insert(hit: MutableArticleHit) {
    if (hit.score > highScore) highScore = hit.score
    insertWithOverflow(hit)
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
  
  def drop(n: Int) {
    var i = 0
    while (i < n) {
      pop()
      i += 1
    }
  }
}

case class ArticleHit(uriId: Id[NormalizedURI], isMyBookmark: Boolean, isPrivate: Boolean, users: Set[Id[User]], score: Float)
case class ArticleSearchResult(hits: Seq[ArticleHit], numMoreHits: Int)

// mutable hit object for efficiency
class MutableArticleHit(var id: Long, var isMyBookmark: Boolean, var isPrivate: Boolean, var score: Float, var users: Set[Id[User]]) {
  def apply(newId: Long, newIsMyBookmark: Boolean, newIsPrivate: Boolean, newScore: Float, newUsers: Set[Id[User]]) = {
    id = newId
    isMyBookmark = newIsMyBookmark
    isPrivate = newIsPrivate
    score = newScore
    users = newUsers
  }
  def toArticleHit = ArticleHit(Id[NormalizedURI](id), isMyBookmark, isPrivate, users, score)
}
