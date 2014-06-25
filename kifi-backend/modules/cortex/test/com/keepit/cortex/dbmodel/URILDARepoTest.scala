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
import com.keepit.model.NormalizedURIStates
import com.keepit.cortex.core.ModelVersion
import com.keepit.cortex.models.lda._


class URILDARepoTest extends Specification with CortexTestInjector {
  "uri lda repo" should {
    "persist and retrieve feature" in {
      withDb() { implicit injector =>
        val uriTopicRepo = inject[URILDATopicRepo]
        val feat = URILDATopic(
          uriId = Id[NormalizedURI](1),
          firstTopic = Some(LDATopic(2)),
          secondTopic = Some(LDATopic(1)),
          thirdTopic = None,
          feature = LDATopicFeature(Array(0.3f, 0.5f, 0.1f, 0.1f)),
          version = ModelVersion[DenseLDA](1),
          uriSeq = SequenceNumber[NormalizedURI](1)
        )

        db.readWrite{ implicit s => uriTopicRepo.save(feat)}

        db.readOnly{ implicit s =>
          uriTopicRepo.getFeature(Id[NormalizedURI](1), ModelVersion[DenseLDA](1)).get.value.toList === List(0.3f, 0.5f, 0.1f, 0.1f)
          val uriTopic = uriTopicRepo.get(Id[URILDATopic](1))
          uriTopic.firstTopic.get.index === 2
          uriTopic.secondTopic.get.index === 1
          uriTopic.thirdTopic === None
          uriTopicRepo.getFeature(Id[NormalizedURI](1), ModelVersion[DenseLDA](2)) === None
          uriTopicRepo.getFeature(Id[NormalizedURI](2), ModelVersion[DenseLDA](1)) === None
        }
      }
    }

    "retrieve hightest seqnum for a version" in {
      withDb() { implicit injector =>
        val uriTopicRepo = inject[URILDATopicRepo]

        db.readWrite { implicit s =>
          (1 to 5).map { i =>
            uriTopicRepo.save(URILDATopic(
              uriId = Id[NormalizedURI](i),
              firstTopic = Some(LDATopic(2)),
              secondTopic = Some(LDATopic(1)),
              thirdTopic = None,
              feature = LDATopicFeature(Array(0.3f, 0.5f, 0.1f, 0.1f)),
              version = ModelVersion[DenseLDA](1),
              uriSeq = SequenceNumber[NormalizedURI](i)))
          }

          (6 to 10).map{ i =>
            uriTopicRepo.save(URILDATopic(
              uriId = Id[NormalizedURI](i),
              firstTopic = Some(LDATopic(2)),
              secondTopic = Some(LDATopic(1)),
              thirdTopic = None,
              feature = LDATopicFeature(Array(0.3f, 0.5f, 0.1f, 0.1f)),
              version = ModelVersion[DenseLDA](2),
              uriSeq = SequenceNumber[NormalizedURI](i)))
          }
        }

        db.readOnly{ implicit s =>
          uriTopicRepo.getHighestSeqNumber(ModelVersion[DenseLDA](1)).value === 5
          uriTopicRepo.getHighestSeqNumber(ModelVersion[DenseLDA](2)).value === 10
          uriTopicRepo.getHighestSeqNumber(ModelVersion[DenseLDA](3)).value === 0
        }

      }
    }
  }
}