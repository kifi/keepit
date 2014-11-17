package com.keepit.model

import org.specs2.mutable.Specification
import com.google.inject.Injector
import com.keepit.common.db.Id
import com.keepit.test._
import com.keepit.common.db.SequenceNumber
import org.joda.time.DateTime
import com.keepit.common.time.DEFAULT_DATE_TIME_ZONE

class ChangedURITest extends Specification with ShoeboxTestInjector {
  "ChangedURIRepo" should {
    "work" in {
      withDb() { implicit injector =>
        val t = new DateTime(2013, 2, 14, 21, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)
        val seqAssigner = inject[ChangedURISeqAssigner]

        db.readOnlyMaster { implicit s =>
          changedURIRepo.getHighestSeqNum() === Some(SequenceNumber.ZERO)
        }

        db.readWrite { implicit s =>
          (1 to 5).map { i =>
            val tmp = ChangedURI(oldUriId = Id[NormalizedURI](i), newUriId = Id[NormalizedURI](i + 100))
            Thread.sleep(2)
            changedURIRepo.save(tmp)
          }
        }

        seqAssigner.assignSequenceNumbers()

        var changes = db.readOnlyMaster { implicit s =>
          changedURIRepo.getChangesSince(SequenceNumber.ZERO, -1, ChangedURIStates.ACTIVE)
        }
        changes.size === 5
        val lastSeq = changes.last.seq
        db.readWrite { implicit s =>
          (6 to 8).map { i =>
            val tmp = ChangedURI(oldUriId = Id[NormalizedURI](i), newUriId = Id[NormalizedURI](i + 100))
            Thread.sleep(2)
            changedURIRepo.save(tmp)
          }
        }

        seqAssigner.assignSequenceNumbers()

        changes = db.readOnlyMaster { implicit s =>
          changedURIRepo.getChangesSince(lastSeq, -1, ChangedURIStates.ACTIVE)
        }

        changes.size === 3

        db.readOnlyMaster { implicit s =>
          changedURIRepo.getHighestSeqNum() === Some(SequenceNumber(8))
          changedURIRepo.getChangesSince(SequenceNumber(0), -1, ChangedURIStates.ACTIVE).map { _.seq.value }.toArray === (1 to 8).toArray
          changedURIRepo.getChangesBetween(SequenceNumber(2), SequenceNumber(6), ChangedURIStates.ACTIVE).map(_.seq.value).toArray === (3 to 6).toArray
        }
      }
    }
  }
}
