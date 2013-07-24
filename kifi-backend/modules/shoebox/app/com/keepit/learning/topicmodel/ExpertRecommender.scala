package com.keepit.learning.topicmodel

import com.keepit.model.UriTopicRepo
import com.keepit.model.UserBookmarkClicksRepo
import com.keepit.common.db.slick.Database
import com.keepit.model.BookmarkRepo
import com.keepit.common.db.Id
import com.keepit.model.User
import com.keepit.model.NormalizedURI
import scala.math.{log => logE}
import com.keepit.common.logging.Logging

class ExpertRecommender(
  db: Database,
  uriTopicRepo: UriTopicRepo,
  clicksRepo: UserBookmarkClicksRepo,
  bookmarkRepo: BookmarkRepo
) extends Logging {
  private def log2(x: Double) = logE(x)/logE(2)

  /**
   * empirical estimation of P(topic | query)
   */
  def estimateTopicPosterior(uris: Seq[Id[NormalizedURI]]): Map[Int, Double] = {
    val topics = db.readOnly { implicit s =>
      uris.flatMap { uri =>
        uriTopicRepo.getAssignedTopicsByUriId(uri) match {
          case Some((Some(a), _)) => Some(a)
          case _ => None
        }
      }
    }
    val topicCounts = topics.groupBy(x => x).mapValues(_.size)
    val s = topicCounts.values.foldLeft(0)(_ + _)
    topicCounts.mapValues(n => n * 1.0 / s)
  }

  /**
   * return: user -> num of hits
   */
  def userHits(urisAndKeepers: Seq[(Id[NormalizedURI], Seq[Id[User]])]): Map[Id[User], Int] = {
    var m = Map.empty[Id[User], Int]
    for((uri, keepers) <- urisAndKeepers){
      for(user <- keepers){
        m += user -> ( m.getOrElse(user, 0) + 1 )
      }
    }
    m
  }

  /**
   * return: user -> all of this user's public bookmark
   */
  def userPublicBookmarks(users: Set[Id[User]]): Map[Id[User], Seq[Id[NormalizedURI]]] = {
    var m = Map.empty[Id[User], Seq[Id[NormalizedURI]]]
    db.readOnly{ implicit s =>
      for(user <- users){
        m += user -> bookmarkRepo.getByUser(user).filter(! _.isPrivate).map(_.uriId)
      }
    }
    m
  }

  /**
   * input: uris = union of all uris of users who are relevant to current hits
   *        topics = possible topics, defined by topics of the hits
   * output: uri -> topic, where topic must be in topics.
   */
  def allRelevantBookmarks(uris: Set[Id[NormalizedURI]], topics: Set[Int]): Map[Id[NormalizedURI], Int] = {
    var m = Map.empty[Id[NormalizedURI], Int]
    db.readOnly{ implicit s =>
      for(uri <- uris){
        uriTopicRepo.getAssignedTopicsByUriId(uri) match {
          case Some( (Some(a), _) ) => if (topics.contains(a)) m += (uri -> a)
          case _ =>
        }
      }
    }
    m
  }

  /**
   * input: userUris: user's uris
   *        relevantBookmarks: return of allRelevantBookmarks
   * output: topic -> related uris
   */
  def userRelevantBookmarks(userUris: Seq[Id[NormalizedURI]], relevantBookmarks: Map[Id[NormalizedURI], Int]): Map[Int, Seq[Id[NormalizedURI]]] = {
    var m = Map.empty[Int, List[Id[NormalizedURI]]]
    for(uri <- userUris){
      relevantBookmarks.get(uri) match {
        case Some(topic) => m += topic -> (uri :: m.getOrElse(topic, List.empty[Id[NormalizedURI]]))
        case _ =>     // this bookmark is not relevant
      }
    }
    m
  }

  /**
   * return: for each user, we know this user's relevant bookmarks (w.r.t topics), and the clicks for each bookmark
   */
  def getAllUserBookmarkClicks(users: Set[Id[User]], allRelevantBookmarks: Map[Id[NormalizedURI], Int], userPublicBookmarks: Map[Id[User], Seq[Id[NormalizedURI]]]): Map[Id[User], Map[Id[NormalizedURI], (Int, Int)]] = {
    var m = Map.empty[Id[User], Map[Id[NormalizedURI], (Int, Int)]]
    db.readOnly{ implicit s =>
      for(user <- users){
        val userUris = userPublicBookmarks.getOrElse(user, List.empty[Id[NormalizedURI]])
        val userRelevantUris = userRelevantBookmarks(userUris, allRelevantBookmarks)          // only those bookmarks relevant to target topics
        val uris = userRelevantUris.values.flatten
        var w = Map.empty[Id[NormalizedURI], (Int, Int)]
        for(uri <- uris){
          clicksRepo.getByUserUri(user, uri) match {
            case Some(r) => w += (uri -> (r.selfClicks, r.otherClicks))
            case None => // bookmark was never clicked by anyone
          }
        }
        m += (user -> w)
      }
    }
    m
  }


  //this should be cached
  def score(userId: Id[User], topic: Int, userRelevantBookmarks: Map[Int, Seq[Id[NormalizedURI]]], allRelevantBookmarks: Map[Id[NormalizedURI], Int],
      userBookmarkClicks: Map[Id[NormalizedURI], (Int, Int)]) = {
    val bookmarkInTopic = userRelevantBookmarks.get(topic) match {
      case Some(x) => x.size
      case None => 0
    }

    val userUris = userRelevantBookmarks.values.flatten
    val (selfClicksSum, otherClicksSum) = userUris.filter(uri => allRelevantBookmarks.getOrElse(uri, -1) == topic).map{ uri =>
      userBookmarkClicks.getOrElse(uri, (0, 0))
    }.foldLeft((0, 0)){case ((selfSum, otherSum), (selfClicks, otherClicks)) => (selfSum + selfClicks, otherSum + otherClicks)}

    val score = bookmarkInTopic + 0.2 * selfClicksSum + 0.8 * otherClicksSum
    log2(1 + score)
  }

  // score a user in any topic she knows about
  def score(userId: Id[User]): Map[Int, Float] = {
    val bms = db.readOnly{ implicit s =>
      bookmarkRepo.getByUser(userId).filter(! _.isPrivate).map(_.uriId)
    }
    val bmAndTopics = db.readOnly{ implicit s =>
      bms.flatMap{ bm => uriTopicRepo.getAssignedTopicsByUriId(bm) match {
        case Some( (Some(a), _) ) => Some((bm, a))
        case _ => None
        }
      }
    }
    val bmsByTopic = bmAndTopics.groupBy(x => x._2).mapValues{x => x.map{_._1}}
    var bmClicks = Map.empty[Id[NormalizedURI], (Int, Int)]
    db.readOnly{ implicit s =>
       bmAndTopics.map{_._1}.foreach{ uriId =>
         clicksRepo.getByUserUri(userId, uriId) match {
           case Some(clicks) => bmClicks += (uriId -> (clicks.selfClicks, clicks.otherClicks))
           case None =>
         }
       }
    }
    var scores = Map.empty[Int, Float]
    for((topic, uris) <- bmsByTopic){
      val clicksInTopic = uris.flatMap{ uriId => bmClicks.get(uriId) }
      val (selfClicksSum, otherClicksSum) = clicksInTopic.foldLeft((0, 0)){case ((selfSum, otherSum), (selfClicks, otherClicks)) => (selfSum + selfClicks, otherSum + otherClicks)}
      val bookmarkInTopic = uris.size
      val score = log2(1 + bookmarkInTopic + 0.2 * selfClicksSum + 0.8 * otherClicksSum)
      scores += (topic -> score.toFloat)
    }
    scores
  }

  // main method
  def rank(urisAndKeepers: Seq[(Id[NormalizedURI], Seq[Id[User]])]) = {
    val users = urisAndKeepers.map{_._2}.flatten.toSet
    val hits = urisAndKeepers.map(_._1)
    log.info(s"num of users: ${users.size}, num of hits: ${hits.size}")
    val userhits = userHits(urisAndKeepers)
    val topicPosterior = estimateTopicPosterior(hits)
    log.info(s"topic posterior: ${topicPosterior.toString}")
    val userBookmarks = userPublicBookmarks(users)
    val uris = userBookmarks.values.flatten.toSet
    val relevantBookmarks = allRelevantBookmarks(uris, topicPosterior.keySet)
    val bookmarkClicks = getAllUserBookmarkClicks(users, relevantBookmarks, userBookmarks)

    val userScores = {
      users.map{ user =>
        val bm = userBookmarks.getOrElse(user, List.empty[Id[NormalizedURI]])
        val userRelevantBm = userRelevantBookmarks(bm, relevantBookmarks)
        var s = 0.0
        for((t, p) <- topicPosterior){
          s += p * score(user, t, userRelevantBm, relevantBookmarks, bookmarkClicks.getOrElse(user, Map.empty[Id[NormalizedURI], (Int, Int)]))
        }
        val boost = userhits.getOrElse(user, 0) * 1.0 / hits.length     // if user's hit num is low, probably she is not quite relevant to the query
        (user, s * boost)
      }.toArray
    }
    log.info(userScores.map{x => x._1.id + ": " + x._2}.mkString(";"))
    userScores.sortBy(-_._2)
  }
}