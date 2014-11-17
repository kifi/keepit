package com.keepit.model

import org.specs2.mutable.Specification
import com.google.inject.Injector
import com.keepit.common.db.Id
import com.keepit.test._
import com.keepit.common.db.SequenceNumber
import org.joda.time.DateTime

class RenormalizedURLTest extends Specification with ShoeboxTestInjector {
  "RenormalizedURLRepo" should {
    "work" in {
      withDb() { implicit injector =>
        val repo = inject[RenormalizedURLRepo]
        val seqAssigner = inject[RenormalizedURLSeqAssigner]

        db.readWrite { implicit s =>
          (1 to 5).map { i =>
            val tmp = RenormalizedURL(urlId = Id[URL](i), oldUriId = Id[NormalizedURI](100), newUriId = Id[NormalizedURI](i))
            repo.save(tmp)
          }
        }

        seqAssigner.assignSequenceNumbers()

        implicit def intToSeq(x: Int) = SequenceNumber(x)

        val records = db.readOnlyMaster { implicit s =>
          val records = repo.getChangesBetween(SequenceNumber(0), SequenceNumber(5), state = RenormalizedURLStates.ACTIVE)
          records.size === 5
          records
        }

        db.readWrite { implicit s =>
          records.foreach { r => repo.saveWithoutIncreSeqnum(r.withState(RenormalizedURLStates.APPLIED)) }
          repo.getChangesBetween(SequenceNumber(0), SequenceNumber(5), state = RenormalizedURLStates.APPLIED).size === 5
        }

      }
    }
  }
}
