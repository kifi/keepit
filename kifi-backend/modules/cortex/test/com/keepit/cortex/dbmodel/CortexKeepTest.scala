package com.keepit.cortex.dbmodel

import org.specs2.mutable.Specification
import com.keepit.test.DbInjectionHelper
import com.keepit.cortex.CortexTestInjector
import org.joda.time.DateTime
import com.keepit.common.time._
import com.keepit.common.db.Id
import com.keepit.model.NormalizedURI
import com.keepit.common.db.State
import com.keepit.common.db.SequenceNumber
import com.keepit.model.Keep
import com.keepit.model.User
import com.keepit.model.KeepSource

class CortexKeepTest extends Specification with CortexTestInjector {
  "cortex keep repo" should {
    "persist and retrieve cortex keep" in {
      withDb() { implicit injector =>
        val keepRepo = inject[CortexKeepRepo]

        db.readOnlyMaster { implicit s =>
          keepRepo.getMaxSeq.value === 0L
        }

        db.readWrite { implicit s =>
          (1 to 10).map { i =>
            val keep = CortexKeep(
              id = None,
              keptAt = new DateTime(2013, 2, 14, 21, 59, 0, 0, DEFAULT_DATE_TIME_ZONE),
              keepId = Id[Keep](i),
              uriId = Id[NormalizedURI](i),
              userId = Some(Id[User](i)),
              isPrivate = false,
              state = State[CortexKeep]("active"),
              source = KeepSource.keeper,
              seq = SequenceNumber[CortexKeep](i)
            )
            keepRepo.save(keep)
          }
        }

        db.readOnlyMaster { implicit s =>
          keepRepo.getSince(SequenceNumber[CortexKeep](5), 10).map { _.keepId.id } === Range(6, 11).toList
          keepRepo.getMaxSeq.value === 10L
        }
      }
    }

    "count recent keeps" in {
      withDb() { implicit injector =>
        val keepRepo = inject[CortexKeepRepo]

        val time = new DateTime(2013, 2, 14, 10, 0, 0, 0, DEFAULT_DATE_TIME_ZONE)

        db.readWrite { implicit s =>
          (1 to 10).map { i =>
            val keep = CortexKeep(
              id = None,
              keptAt = time.plusDays(i),
              keepId = Id[Keep](i),
              uriId = Id[NormalizedURI](i),
              userId = Some(Id[User](1)),
              isPrivate = false,
              state = State[CortexKeep]("active"),
              source = KeepSource.keeper,
              seq = SequenceNumber[CortexKeep](i)
            )
            keepRepo.save(keep)
          }
        }

        db.readOnlyReplica { implicit s =>
          keepRepo.countRecentUserKeeps(Id[User](1), time) === 10
        }

        var since = time.plusDays(5)
        db.readOnlyReplica { implicit s =>
          keepRepo.countRecentUserKeeps(Id[User](1), since) === 5
        }

        since = time.plusDays(10)
        db.readOnlyReplica { implicit s =>
          keepRepo.countRecentUserKeeps(Id[User](1), since) === 0
        }

      }
    }

  }
}
