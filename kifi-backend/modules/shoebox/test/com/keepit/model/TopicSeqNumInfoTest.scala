package com.keepit.model
import org.specs2.mutable.Specification
import com.keepit.test._
import com.keepit.common.db.SequenceNumber


class TopicSeqNumInfoTest extends Specification with ShoeboxTestInjector {
  "TopicSeqNumInfoRepo" should {
    "work" in {
      withDb() { implicit injector =>
        val topicSeqNumInfoRepo = inject[TopicSeqNumInfoRepoA]
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