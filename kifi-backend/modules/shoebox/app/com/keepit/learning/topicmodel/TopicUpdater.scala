package com.keepit.learning.topicmodel

import com.keepit.search.ArticleStore
import com.google.inject.Inject
import com.keepit.common.db.slick.Database
import com.keepit.model._
import com.keepit.common.db.Id
import com.keepit.common.logging.Logging
import scala.collection.mutable.{Map => MutMap}
import com.keepit.common.db.SequenceNumber
import com.google.inject.ImplementedBy
import scala.Option.option2Iterable
import scala.collection.mutable.{Map => MutMap}
import com.google.inject.Singleton
import scala.collection.mutable.{Map => MutMap}


class TopicUpdater @Inject() (
  db: Database,
  uriRepo: NormalizedURIRepo,
  userTopicRepo: UserTopicRepo,
  uriTopicRepo: UriTopicRepo,
  topicSeqInfoRepo: TopicSeqNumInfoRepo,
  bookmarkRepo: BookmarkRepo,
  articleStore: ArticleStore,
  documentTopicModel: DocumentTopicModel
) extends Logging {
  val commitBatchSize = 100
  val fetchSize = 5000
  val uriTopicHelper = new UriTopicHelper
  val userTopicHelper = new UserTopicByteArrayHelper

  // main entry point
  def update(): Unit = {
    log.info("==============topicUpdater: starting a new round of update =============")
    val (uriSeq, bookmarkSeq) = db.readOnly { implicit s =>
      topicSeqInfoRepo.getSeqNums match {
        case Some((uriSeq, bookmarkSeq)) => (uriSeq, bookmarkSeq)
        case None => (SequenceNumber.ZERO, SequenceNumber.ZERO)
      }
    }
    updateUriTopic(uriSeq)
    updateUserTopic(bookmarkSeq)
  }

  private def updateUriTopic(seqNum: SequenceNumber): Unit = {
    def getOverdueUris(seqNum: SequenceNumber): Seq[NormalizedURI] = {
      db.readOnly { implicit s =>
        uriRepo.getIndexable(seqNum, fetchSize)
      }
    }

    val uris = getOverdueUris(seqNum)
    val rounds = uris.size / commitBatchSize
    val left = uris.size % commitBatchSize
    log.info(s"${uris.size} uris changed. Updating UriTopicRepo")

    (0 until rounds).foreach { i =>
      log.info(s"uri topic update round ${i + 1} of $rounds")
      batchUpdateUriTopic(uris.slice(i * commitBatchSize, (i + 1) * commitBatchSize))
    }
    if ( left > 0) batchUpdateUriTopic(uris.slice(rounds * commitBatchSize, rounds * commitBatchSize + left))
    log.info("UriTopicRepo update done")

  }

  private def updateUserTopic(seqNum: SequenceNumber): Unit = {
    def getOverdueUserUris(seqNum: SequenceNumber): Seq[Bookmark] = {
      db.readOnly { implicit s =>
        bookmarkRepo.getBookmarksChanged(seqNum, fetchSize)
      }
    }

    val userBookmarks = getOverdueUserUris(seqNum)
    val rounds = userBookmarks.size / commitBatchSize
    val left = userBookmarks.size % commitBatchSize
    log.info(s"${userBookmarks.size} bookmarks have been changed. Updating UserTopicTable")
    (0 until rounds).foreach { i =>
      log.info(s"user topic update round ${i + 1} of $rounds")
      batchUpdateUserTopic(userBookmarks.slice(i * commitBatchSize, (i + 1) * commitBatchSize))
    }
    if (left > 0 ) batchUpdateUserTopic(userBookmarks.slice(rounds * commitBatchSize, rounds * commitBatchSize + left))
    log.info("UserTopicRepo update done")
  }

  private def batchUpdateUriTopic(uris: Seq[NormalizedURI]): Unit = {
    if ( uris.size > 0 ) {
      val uriTopics = getTopicForUris(uris.flatMap { _.id })
      db.readWrite { implicit s =>
        uriTopics.foreach { x =>
          uriTopicRepo.getByUriId(x.uriId) match {
            case Some(uriTopic) => uriTopicRepo.save(uriTopic.copy(topic = x.topic, primaryTopic = x.primaryTopic, secondaryTopic = x.secondaryTopic))
            case None => uriTopicRepo.save(x)
          }
        }
        val largestSeq = uris.sortBy(_.seq).last.seq
        log.info("updating uri_seq in topicSeqInfoRepo to" + largestSeq)
        topicSeqInfoRepo.updateUriSeq(largestSeq)
      }
    }
  }

  private def getUriContent(uriId: Id[NormalizedURI]): String = {
    articleStore.get(uriId) match {
      case Some(article) => article.content
      case None => ""
    }
  }

  private def getTopicForUris(uris: Seq[Id[NormalizedURI]]) = {
    uris.map { uriId =>
      val c = getUriContent(uriId)
      genUriTopic(uriId, c)
    }
  }

  private def genUriTopic(uriId: Id[NormalizedURI], uriContent: String): UriTopic = {
    val topic = documentTopicModel.getDocumentTopicDistribution(uriContent)
    val (primaryTopic, secondaryTopic) = uriTopicHelper.assignTopics(topic)
    UriTopic(uriId = uriId, topic = uriTopicHelper.toByteArray(topic), primaryTopic = primaryTopic, secondaryTopic = secondaryTopic)
  }

  def batchUpdateUserTopic(bookmarks: Seq[Bookmark]): Unit = {
    if (bookmarks.size > 0) {
      val userBookmarks = groupBookmarksByUser(bookmarks)
      val bookmarkTopics = getBookmarkTopics(bookmarks.map(_.uriId))
      val userTopics = getUserTopics(userBookmarks, bookmarkTopics)
      db.readWrite { implicit s =>
        userTopics.foreach{ userTopic =>
          val oldTopic = userTopicRepo.getByUserId(userTopic._1)
          if (oldTopic == None){
            val topic = new Array[Int](TopicModelGlobal.numTopics)
            userTopic._2.foreach{ case (topicIdx, counts) => topic(topicIdx) += counts;
              if (topic(topicIdx) < 0) { topic(topicIdx) = 0; log.warn("was trying to set user topic to negative")}
            }
            // insert new record
            userTopicRepo.save(UserTopic(userId = userTopic._1, topic = userTopicHelper.toByteArray(topic)))
          } else {
            val topic = userTopicHelper.toIntArray(oldTopic.get.topic)
            userTopic._2.foreach{ case (topicIdx, counts) => topic(topicIdx) += counts;
              if (topic(topicIdx) < 0) { topic(topicIdx) = 0; log.warn("was trying to set user topic to negative")}
            }
            userTopicRepo.save(oldTopic.get.copy(topic = userTopicHelper.toByteArray(topic)))
          }
        }
        val largestSeq = bookmarks.sortBy(_.seq).last.seq
        log.info("updating bookmark_seq in topicSeqInfoRepo to" + largestSeq.value)
        topicSeqInfoRepo.updateBookmarkSeq(largestSeq)
      }
    }
  }

  // return: user -> (keptPages, unkeptPages)
  private def groupBookmarksByUser(bookmarks: Seq[Bookmark]): Map[Id[User], (Seq[Id[NormalizedURI]], Seq[Id[NormalizedURI]])] = {
    val group = bookmarks.groupBy(bm => bm.userId)
    group.foldLeft(Map.empty[Id[User], (Seq[Id[NormalizedURI]], Seq[Id[NormalizedURI]])]){
      (m, userBookmarks) => {
        val (userId, bookmarks) = userBookmarks
        val partition = bookmarks.filter(!_.isPrivate).partition(_.isActive)
        val (kept, unkept) = (partition._1.map{bm => bm.uriId}, partition._2.map{bm => bm.uriId})
        m + (userId -> (kept, unkept))
      }
    }
  }

  private def getBookmarkTopics(uris: Seq[Id[NormalizedURI]]): Map[Id[NormalizedURI], (Option[Int], Option[Int])] = {
    db.readOnly{ implicit s =>
      uris.foldLeft(Map.empty[Id[NormalizedURI], (Option[Int], Option[Int])]){ (m, uriId) =>
        uriTopicRepo.getAssignedTopicsByUriId(uriId) match {
          case Some((primary, secondary)) => m + (uriId -> (primary, secondary))
          case None => {
            val article = articleStore.get(uriId)
            if (article != None && article.get.contentLang != "en") m + (uriId -> (None, None))
            else {
              if (article == None) { log.warn(s"uri ${uriId.id} is not found in uriTopicRepo, and it's not found in articleStore"); m + (uriId -> (None, None)) }
              else {
                val topic = documentTopicModel.getDocumentTopicDistribution(article.get.content)
                val (primaryTopic, secondaryTopic) = uriTopicHelper.assignTopics(topic)
                m + (uriId -> (primaryTopic, secondaryTopic))
              }
            }
          }
        }
      }
    }
  }

  /**
   * input: user's bookmarks (kept and unkept ones); uris' topics
   * output: map of user -> topic counts. (Int, Int) = (topicIdx, num of uris assigned to this topic)
   */
  private def getUserTopics(userBookmarks: Map[Id[User], (Seq[Id[NormalizedURI]], Seq[Id[NormalizedURI]])],
    bookmarkTopics: Map[Id[NormalizedURI], (Option[Int], Option[Int])]): Map[Id[User], Map[Int, Int]] = {
    userBookmarks.foldLeft(Map.empty[Id[User], Map[Int, Int]]) { (m, userBms) =>
      {
        val userId = userBms._1
        val (kept, unkept) = userBms._2
        val topicCounts = MutMap.empty[Int, Int]
        for (uri <- kept) {
          val topic = bookmarkTopics.get(uri)
          if (topic != None) {
            val (primary, secondary) = topic.get
            if (primary != None) { val idx = primary.get; topicCounts(idx) = topicCounts.getOrElse(idx, 0) + 1 }
            if (secondary != None) { val idx = secondary.get; topicCounts(idx) = topicCounts.getOrElse(idx, 0) + 1 }
          }
        }

        for (uri <- unkept) {
          val topic = bookmarkTopics.get(uri)
          if (topic != None) {
            val (primary, secondary) = topic.get
            if (primary != None) { val idx = primary.get; topicCounts(idx) = topicCounts.getOrElse(idx, 0) - 1 }
            if (secondary != None) { val idx = secondary.get; topicCounts(idx) = topicCounts.getOrElse(idx, 0) - 1 }
          }
        }
        m + (userId -> topicCounts.toMap[Int, Int])
      }
    }
  }

}