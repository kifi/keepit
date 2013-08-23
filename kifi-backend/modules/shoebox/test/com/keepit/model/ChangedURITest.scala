package com.keepit.model

import org.specs2.mutable.Specification
import com.google.inject.Injector
import com.keepit.common.db.Id
import com.keepit.test._
import com.keepit.common.db.SequenceNumber
import org.joda.time.DateTime
import com.keepit.common.time.zones.PT

class ChangedURITest extends Specification with ShoeboxTestInjector{
  "ChangedURIRepo" should {
    "work" in {
       withDb() { implicit injector =>
         val t = new DateTime(2013, 2, 14, 21, 59, 0, 0, PT)
         
         db.readOnly{ implicit s =>
           changedURIRepo.getHighestSeqNum() === None
         }

         db.readWrite { implicit s =>
           (1 to 5).map{ i =>
             val tmp = ChangedURI(oldUriId = Id[NormalizedURI](i), newUriId = Id[NormalizedURI](i+100))
             Thread.sleep(2)
             changedURIRepo.save(tmp)
           }
         }

         var changes = db.readOnly{ implicit s =>
           changedURIRepo.getChangesSince(SequenceNumber.ZERO, -1, ChangedURIStates.ACTIVE)
         }
         changes.size === 5
         val lastSeq = changes.last.seq
         db.readWrite { implicit s =>
           (6 to 8).map{ i =>
             val tmp = ChangedURI(oldUriId = Id[NormalizedURI](i), newUriId = Id[NormalizedURI](i+100))
             Thread.sleep(2)
             changedURIRepo.save(tmp)
           }
         }

         changes = db.readOnly{ implicit s =>
           changedURIRepo.getChangesSince(lastSeq, -1, ChangedURIStates.ACTIVE)
         }

         changes.size === 3

         db.readOnly { implicit s =>
           changedURIRepo.getHighestSeqNum() === Some(SequenceNumber(8))
           changedURIRepo.getChangesSince(SequenceNumber(0), -1, ChangedURIStates.ACTIVE).map{_.seq.value}.toArray === (1 to 8).toArray
           changedURIRepo.getChangesBetween(SequenceNumber(2), SequenceNumber(6), ChangedURIStates.ACTIVE).map(_.seq.value).toArray === (3 to 6).toArray
     
           changedURIRepo.page(0, 3).map(_.seq.value).toArray === Array(8, 7, 6)
           changedURIRepo.page(1, 3).map(_.seq.value).toArray === Array(5, 4, 3)
         }
       }
    }
  }
}
