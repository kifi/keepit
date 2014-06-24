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


class CortexURITest extends Specification with CortexTestInjector{
  "cortex uri repo" should {
    "persist and retrieve cortex uri" in {
      withDb() { implicit injector =>
        val uriRepo = inject[CortexURIRepo]

        val uris = (1 to 10).map{ i =>
          CortexURI(
           id = None,
           uriId = Id[NormalizedURI](i),
           state = State[CortexURI]("active"),
           seq = SequenceNumber[CortexURI](i)
         )
        }

        db.readOnly{ implicit s =>
          uriRepo.getMaxSeq.value === 0L
        }

        db.readWrite{ implicit s =>
          uris.foreach{ uriRepo.save(_)}
        }

        db.readOnly{ implicit s =>
          uriRepo.getSince(SequenceNumber[CortexURI](5), 10).map{_.uriId.id} === Range(6, 11).toList
          uriRepo.getMaxSeq.value === 10L
        }
      }
    }
  }
}
