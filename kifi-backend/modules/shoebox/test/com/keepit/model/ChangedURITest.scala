package com.keepit.model

import org.specs2.mutable.Specification
import com.google.inject.Injector
import com.keepit.common.db.Id
import com.keepit.test._
import com.keepit.common.db.SequenceNumber

class ChangedURITest extends Specification with ShoeboxTestInjector{
  "ChangedURIRepo" should {
    "work" in {
       withDb() { implicit injector =>

         db.readOnly{ implicit s =>
           changedURIRepo.getHighestSeqNum() === None
         }

         db.readWrite { implicit s =>
           (1 to 5).map{ i =>
             val tmp = ChangedURI(oldUriId = Id[NormalizedURI](i), newUriId = Id[NormalizedURI](i+100))
             changedURIRepo.save(tmp)
           }
         }

         var changes = db.readOnly{ implicit s =>
           changedURIRepo.getChangesSince(SequenceNumber.ZERO, -1)
         }
         changes.size === 5
         val lastSeq = changes.last.seq
         db.readWrite { implicit s =>
           (6 to 8).map{ i =>
             val tmp = ChangedURI(oldUriId = Id[NormalizedURI](i), newUriId = Id[NormalizedURI](i+100))
             changedURIRepo.save(tmp)
           }
         }

         changes = db.readOnly{ implicit s =>
           changedURIRepo.getChangesSince(lastSeq, -1)
         }

         changes.size === 3

         db.readOnly { implicit s =>
           changedURIRepo.getHighestSeqNum() === Some(SequenceNumber(8))
         }
         
         db.readOnly { implicit s =>
           changedURIRepo.getChangesBetween(SequenceNumber(2), SequenceNumber(6)).map(_.seq.value).toArray === (3 to 6).toArray
         }
       }
    }
  }
}
