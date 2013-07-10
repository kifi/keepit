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

@Singleton
class TopicUpdater @Inject() (
  db: Database,
  uriRepo: NormalizedURIRepo,
  bookmarkRepo: BookmarkRepo,
  articleStore: ArticleStore,
  modelAccessor: SwitchableTopicModelAccessor
) extends Logging {

  def getAccessor(useActive: Boolean) = if (useActive) modelAccessor.getActiveAccessor else modelAccessor.getInactiveAccessor
  val commitBatchSize = 100
  val fetchSize = 5000
  val uriTopicHelper = new UriTopicHelper
  val userTopicHelper = new UserTopicByteArrayHelper

  def numTopics(useActive: Boolean) = getAccessor(useActive).topicNameMapper.rawTopicNames.size

  def reset(useActive: Boolean = true): (Int, Int) = {
    log.info("resetting topic tables")
    val (nUri, nUser) = db.readWrite { implicit s =>
      getAccessor(useActive).topicSeqInfoRepo.updateUriSeq(SequenceNumber.ZERO)
      getAccessor(useActive).topicSeqInfoRepo.updateBookmarkSeq(SequenceNumber.ZERO)
      val nUri = getAccessor(useActive).uriTopicRepo.deleteAll()
      val nUser = getAccessor(useActive).userTopicRepo.deleteAll()
      (nUri, nUser)
    }
    log.info(s"resetting topic tables successfully. ${nUri} uris removed. ${nUser} users removed.")
    (nUri, nUser)
  }

  // main entry point
  def update(useActive: Boolean = true): (Int, Int) = {
    log.info("TopicUpdater: starting a new round of update ...")
    val (uriSeq, bookmarkSeq) = db.readOnly { implicit s =>
      getAccessor(useActive).topicSeqInfoRepo.getSeqNums match {
        case Some((uriSeq, bookmarkSeq)) => (uriSeq, bookmarkSeq)
        case None => (SequenceNumber.ZERO, SequenceNumber.ZERO)
      }
    }
    val m = updateUriTopic(uriSeq, useActive)
    val n = updateUserTopic(bookmarkSeq, useActive)
    (m, n)
  }

  def remodel() = {
    log.info("TopicUpdater: start remodelling ...")
    log.info(s"current model is ${modelAccessor.getCurrentFlag}. will update using the other model")
    reset(useActive = false)
    var catchUp = false
    while (!catchUp) {
      val (m, n) = update(useActive = false)
      if (m.max(n) < fetchSize) catchUp = true
    }
    modelAccessor.switchAccessor()
    log.info(s"successfully switched to model ${modelAccessor.getCurrentFlag}")
  }

  private def updateUriTopic(seqNum: SequenceNumber, useActive: Boolean): Int = {
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
      batchUpdateUriTopic(uris.slice(i * commitBatchSize, (i + 1) * commitBatchSize), useActive)
    }
    if ( left > 0) batchUpdateUriTopic(uris.slice(rounds * commitBatchSize, rounds * commitBatchSize + left), useActive)
    log.info("UriTopicRepo update done")
    uris.size
  }

  private def updateUserTopic(seqNum: SequenceNumber, useActive: Boolean): Int = {
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
      batchUpdateUserTopic(userBookmarks.slice(i * commitBatchSize, (i + 1) * commitBatchSize), useActive)
    }
    if (left > 0 ) batchUpdateUserTopic(userBookmarks.slice(rounds * commitBatchSize, rounds * commitBatchSize + left), useActive)
    log.info("UserTopicRepo update done")
    userBookmarks.size
  }

  private def batchUpdateUriTopic(uris: Seq[NormalizedURI], useActive: Boolean): Unit = {
    if ( uris.size > 0 ) {
      val uriTopics = getTopicForUris(uris.flatMap { _.id }, useActive)
      db.readWrite { implicit s =>
        uriTopics.foreach { x =>
          getAccessor(useActive).uriTopicRepo.getByUriId(x.uriId) match {
            case Some(uriTopic) => getAccessor(useActive).uriTopicRepo.save(uriTopic.copy(topic = x.topic, primaryTopic = x.primaryTopic, secondaryTopic = x.secondaryTopic))
            case None => getAccessor(useActive).uriTopicRepo.save(x)
          }
        }
        val largestSeq = uris.sortBy(_.seq).last.seq
        log.info("updating uri_seq in topicSeqInfoRepo to " + largestSeq)
        getAccessor(useActive).topicSeqInfoRepo.updateUriSeq(largestSeq)
      }
    }
  }

  private def getUriContent(uriId: Id[NormalizedURI]): String = {
    articleStore.get(uriId) match {
      case Some(article) =>  if (article.contentLang != None && article.contentLang.get.lang == "en") article.content else ""
      case None => ""
    }
  }

  private def getTopicForUris(uris: Seq[Id[NormalizedURI]], useActive: Boolean) = {
    uris.map { uriId =>
      val c = getUriContent(uriId)
      genUriTopic(uriId, c, useActive)
    }
  }

  private def genUriTopic(uriId: Id[NormalizedURI], uriContent: String, useActive: Boolean): UriTopic = {
    val numTopic = numTopics(useActive)
    val topic = getAccessor(useActive).documentTopicModel.getDocumentTopicDistribution(uriContent, numTopic)
    val (primaryTopic, secondaryTopic) = uriTopicHelper.assignTopics(topic, numTopic)
    UriTopic(uriId = uriId, topic = uriTopicHelper.toByteArray(topic, numTopic), primaryTopic = primaryTopic, secondaryTopic = secondaryTopic)
  }

  def batchUpdateUserTopic(bookmarks: Seq[Bookmark], useActive: Boolean): Unit = {
    if (bookmarks.size > 0) {
      val userBookmarks = groupBookmarksByUser(bookmarks)
      val bookmarkTopics = getBookmarkTopics(bookmarks.map(_.uriId), useActive)
      val userTopics = getUserTopics(userBookmarks, bookmarkTopics)
      db.readWrite { implicit s =>
        userTopics.foreach{ userTopic =>
          val oldTopic = getAccessor(useActive).userTopicRepo.getByUserId(userTopic._1)
          if (oldTopic == None){
            val topic = new Array[Int](numTopics(useActive))
            userTopic._2.foreach{ case (topicIdx, counts) => topic(topicIdx) += counts;
              if (topic(topicIdx) < 0) { topic(topicIdx) = 0; log.warn("was trying to set user topic to negative")}
            }
            // insert new record
            getAccessor(useActive).userTopicRepo.save(UserTopic(userId = userTopic._1, topic = userTopicHelper.toByteArray(topic)))
          } else {
            val topic = userTopicHelper.toIntArray(oldTopic.get.topic)
            userTopic._2.foreach{ case (topicIdx, counts) => topic(topicIdx) += counts;
              if (topic(topicIdx) < 0) { topic(topicIdx) = 0; log.warn("was trying to set user topic to negative")}
            }
            getAccessor(useActive).userTopicRepo.save(oldTopic.get.copy(topic = userTopicHelper.toByteArray(topic)))
          }
        }
        val largestSeq = bookmarks.sortBy(_.seq).last.seq
        log.info("updating bookmark_seq in topicSeqInfoRepo to " + largestSeq.value)
        getAccessor(useActive).topicSeqInfoRepo.updateBookmarkSeq(largestSeq)
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

  private def getBookmarkTopics(uris: Seq[Id[NormalizedURI]], useActive: Boolean): Map[Id[NormalizedURI], (Option[Int], Option[Int])] = {
    val numTopic = numTopics(useActive)
    db.readOnly{ implicit s =>
      uris.foldLeft(Map.empty[Id[NormalizedURI], (Option[Int], Option[Int])]){ (m, uriId) =>
        getAccessor(useActive).uriTopicRepo.getAssignedTopicsByUriId(uriId) match {
          case Some((primary, secondary)) => m + (uriId -> (primary, secondary))
          case None => {
            val article = articleStore.get(uriId)
            if (article != None && (article.get.contentLang == None || article.get.contentLang.get.lang != "en")) m + (uriId -> (None, None))
            else {
              if (article == None) { log.warn(s"uri ${uriId.id} is not found in uriTopicRepo, and it's not found in articleStore"); m + (uriId -> (None, None)) }
              else {
                val topic = getAccessor(useActive).documentTopicModel.getDocumentTopicDistribution(article.get.content, numTopics(useActive))
                val (primaryTopic, secondaryTopic) = uriTopicHelper.assignTopics(topic, numTopic)
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