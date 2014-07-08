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


class URILDATopicRepoTest extends Specification with CortexTestInjector {
  "uri lda repo" should {
    "persist and retrieve feature" in {
      withDb() { implicit injector =>
        val uriTopicRepo = inject[URILDATopicRepo]
        val feat = URILDATopic(
          uriId = Id[NormalizedURI](1),
          firstTopic = Some(LDATopic(2)),
          secondTopic = Some(LDATopic(1)),
          thirdTopic = None,
          sparseFeature = Some(SparseTopicRepresentation(dimension = 4, topics = Map(LDATopic(2) -> 0.5f, LDATopic(1) -> 0.3f))),
          feature = Some(LDATopicFeature(Array(0.3f, 0.5f, 0.1f, 0.1f))),
          version = ModelVersion[DenseLDA](1),
          uriSeq = SequenceNumber[NormalizedURI](1),
          state = URILDATopicStates.ACTIVE
        )

        val feat2 = URILDATopic(uriId = Id[NormalizedURI](2), version = ModelVersion[DenseLDA](1), uriSeq = SequenceNumber[NormalizedURI](2), state = URILDATopicStates.NOT_APPLICABLE)

        db.readWrite{ implicit s =>
          uriTopicRepo.save(feat);
          uriTopicRepo.save(feat2)
        }

        db.readOnlyMaster{ implicit s =>
          uriTopicRepo.getFeature(Id[NormalizedURI](1), ModelVersion[DenseLDA](1)).get.value.toList === List(0.3f, 0.5f, 0.1f, 0.1f)
          uriTopicRepo.getUpdateTimeAndState(Id[NormalizedURI](1), ModelVersion[DenseLDA](1)).get._2 === URILDATopicStates.ACTIVE

          val uriTopic = uriTopicRepo.get(Id[URILDATopic](1))
          uriTopic.firstTopic.get.index === 2
          uriTopic.secondTopic.get.index === 1
          uriTopic.thirdTopic === None
          uriTopic.sparseFeature.get.dimension === 4
          uriTopic.sparseFeature.get.topics === Map(LDATopic(2) -> 0.5f, LDATopic(1) -> 0.3f)


          uriTopicRepo.getFeature(Id[NormalizedURI](2), ModelVersion[DenseLDA](1)) === None
          uriTopicRepo.getUpdateTimeAndState(Id[NormalizedURI](2), ModelVersion[DenseLDA](1)).get._2 === URILDATopicStates.NOT_APPLICABLE
          uriTopicRepo.getByURI(Id[NormalizedURI](2), ModelVersion[DenseLDA](1)).get.state === URILDATopicStates.NOT_APPLICABLE

          val topic2 = uriTopicRepo.get(Id[URILDATopic](2))
          topic2.sparseFeature === None
          topic2.feature === None

          uriTopicRepo.getFeature(Id[NormalizedURI](1), ModelVersion[DenseLDA](2)) === None
          uriTopicRepo.getFeature(Id[NormalizedURI](3), ModelVersion[DenseLDA](1)) === None
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
              sparseFeature = Some(SparseTopicRepresentation(dimension = 4, topics = Map(LDATopic(2) -> 0.5f, LDATopic(1) -> 0.3f))),
              feature = Some(LDATopicFeature(Array(0.3f, 0.5f, 0.1f, 0.1f))),
              version = ModelVersion[DenseLDA](1),
              uriSeq = SequenceNumber[NormalizedURI](i),
              state = URILDATopicStates.ACTIVE))
          }

          (6 to 10).map{ i =>
            uriTopicRepo.save(URILDATopic(
              uriId = Id[NormalizedURI](i),
              firstTopic = Some(LDATopic(2)),
              secondTopic = Some(LDATopic(1)),
              thirdTopic = None,
              sparseFeature = Some(SparseTopicRepresentation(dimension = 4, topics = Map(LDATopic(2) -> 0.5f, LDATopic(1) -> 0.3f))),
              feature = Some(LDATopicFeature(Array(0.3f, 0.5f, 0.1f, 0.1f))),
              version = ModelVersion[DenseLDA](2),
              uriSeq = SequenceNumber[NormalizedURI](i),
              state = URILDATopicStates.ACTIVE))
          }
        }

        db.readOnlyMaster{ implicit s =>
          uriTopicRepo.getHighestSeqNumber(ModelVersion[DenseLDA](1)).value === 5
          uriTopicRepo.getHighestSeqNumber(ModelVersion[DenseLDA](2)).value === 10
          uriTopicRepo.getHighestSeqNumber(ModelVersion[DenseLDA](3)).value === 0
        }

      }
    }
  }
}
