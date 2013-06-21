package com.keepit.model
import com.keepit.common.time._
import org.specs2.mutable.Specification
import play.api.test._
import play.api.test.Helpers._
import com.keepit.test._
import com.keepit.inject._
import play.api.Play.current
import com.keepit.common.db.SequenceNumber


class TopicSeqNumInfoTest extends Specification with TestDBRunner{
  "TopicSeqNumInfoRepo" should {
    "work" in {
      withDB() { implicit injector =>
        val topicSeqNumInfoRepo = inject[TopicSeqNumInfoRepo]
        db.readWrite { implicit s =>
          val s1 = topicSeqNumInfoRepo.updateBookmarkSeq(SequenceNumber(1))
          val s2 = topicSeqNumInfoRepo.updateUriSeq(SequenceNumber(1))
          s2.bookmarkSeq === SequenceNumber(1)
          s2.uriSeq === SequenceNumber(1)
        }
      }
    }
  }
}