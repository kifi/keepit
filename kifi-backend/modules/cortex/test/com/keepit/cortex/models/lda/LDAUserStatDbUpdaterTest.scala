package com.keepit.cortex.models.lda

import com.keepit.common.db.{ SequenceNumber, State, Id }
import com.keepit.common.time._
import com.keepit.cortex.CortexTestInjector
import com.keepit.cortex.core.ModelVersion
import com.keepit.cortex.dbmodel._
import com.keepit.model.{ NormalizedURI, KeepSource, Keep, User }
import org.joda.time.DateTime
import org.specs2.mutable.Specification

class LDAUserStatDbUpdaterTest extends Specification with CortexTestInjector with LDADbTestHelper {
  "lda user stat db updater " should {
    "work" in {
      withDb() { implicit injector =>
        val keepRepo = inject[CortexKeepRepo]
        val commitRepo = inject[FeatureCommitInfoRepo]
        val uriTopicRepo = inject[URILDATopicRepo]
        val userLDAStatsRepo = inject[UserLDAStatsRepo]
        val time = new DateTime(2014, 1, 30, 17, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)

        db.readWrite { implicit s =>
          keepRepo.save(CortexKeep(keptAt = time, userId = Id[User](1), keepId = Id[Keep](1), uriId = Id[NormalizedURI](1L), isPrivate = false, state = State[CortexKeep]("active"), seq = SequenceNumber[CortexKeep](1L), source = KeepSource.keeper))
          keepRepo.save(CortexKeep(keptAt = time, userId = Id[User](1), keepId = Id[Keep](2), uriId = Id[NormalizedURI](2L), isPrivate = false, state = State[CortexKeep]("active"), seq = SequenceNumber[CortexKeep](2L), source = KeepSource.keeper))
          uriTopicRepo.save(URILDATopic(uriId = Id[NormalizedURI](1L), uriSeq = SequenceNumber[NormalizedURI](1L), version = ModelVersion[DenseLDA](1), numOfWords = 200, feature = Some(LDATopicFeature(Array(0f, 1f))), state = URILDATopicStates.ACTIVE))
          uriTopicRepo.save(URILDATopic(uriId = Id[NormalizedURI](2L), uriSeq = SequenceNumber[NormalizedURI](2L), version = ModelVersion[DenseLDA](1), numOfWords = 200, feature = Some(LDATopicFeature(Array(1f, 0f))), state = URILDATopicStates.ACTIVE))
        }

        val updater = new LDAUserStatDbUpdaterImpl(uriRep, db, keepRepo, uriTopicRepo, userLDAStatsRepo, commitRepo) {
          override val min_num_evidence = 1
        }

        updater.update()

        db.readOnlyReplica { implicit s =>
          val model = userLDAStatsRepo.getByUser(Id[User](1), uriRep.version).get
          model.numOfEvidence === 2
          model.userTopicMean.get.mean.toList === List(0.5f, 0.5f)
          model.userTopicVar.get.value.toList.forall(x => math.abs(x - 0.5f) < 1e-4) === true
        }
      }
    }
  }
}
