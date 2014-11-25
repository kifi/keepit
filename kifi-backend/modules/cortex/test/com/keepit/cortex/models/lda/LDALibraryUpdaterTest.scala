package com.keepit.cortex.models.lda

import com.keepit.common.db.{ SequenceNumber, State, Id }
import com.keepit.common.time._
import com.keepit.cortex.CortexTestInjector
import com.keepit.cortex.core.{ StatModelName, ModelVersion }
import com.keepit.cortex.dbmodel._
import com.keepit.model._
import org.joda.time.DateTime
import org.specs2.mutable.Specification

class LDALibraryUpdaterTest extends Specification with CortexTestInjector with LDADbTestHelper {
  "lda library updater" should {
    "work" in {
      withDb() { implicit injector =>
        val keepRepo = inject[CortexKeepRepo]
        val libRepo = inject[CortexLibraryRepo]
        val commitRepo = inject[FeatureCommitInfoRepo]
        val uriTopicRepo = inject[URILDATopicRepo]
        val libLDARepo = inject[LibraryLDATopicRepo]
        val updater = new LDALibraryUpdaterImpl(uriReps, db, keepRepo, libRepo, libLDARepo, uriTopicRepo, commitRepo)

        val time = new DateTime(2014, 1, 30, 17, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)

        db.readWrite { implicit s =>
          keepRepo.save(CortexKeep(keptAt = time, userId = Id[User](1), keepId = Id[Keep](1), uriId = Id[NormalizedURI](1L), isPrivate = false, state = State[CortexKeep]("active"), seq = SequenceNumber[CortexKeep](1L), source = KeepSource.keeper, libraryId = Some(Id[Library](1))))
          keepRepo.save(CortexKeep(keptAt = time, userId = Id[User](1), keepId = Id[Keep](2), uriId = Id[NormalizedURI](2L), isPrivate = false, state = State[CortexKeep]("active"), seq = SequenceNumber[CortexKeep](2L), source = KeepSource.keeper, libraryId = Some(Id[Library](1))))
          keepRepo.save(CortexKeep(keptAt = time, userId = Id[User](1), keepId = Id[Keep](3), uriId = Id[NormalizedURI](3L), isPrivate = false, state = State[CortexKeep]("active"), seq = SequenceNumber[CortexKeep](3L), source = KeepSource.keeper, libraryId = Some(Id[Library](2))))
          uriTopicRepo.save(URILDATopic(uriId = Id[NormalizedURI](1L), uriSeq = SequenceNumber[NormalizedURI](1L), version = ModelVersion[DenseLDA](1), numOfWords = 200, feature = Some(LDATopicFeature(Array(0f, 1f, 0f))), state = URILDATopicStates.ACTIVE))
          uriTopicRepo.save(URILDATopic(uriId = Id[NormalizedURI](2L), uriSeq = SequenceNumber[NormalizedURI](2L), version = ModelVersion[DenseLDA](1), numOfWords = 200, feature = Some(LDATopicFeature(Array(0.2f, 0f, 0.8f))), state = URILDATopicStates.ACTIVE))
          uriTopicRepo.save(URILDATopic(uriId = Id[NormalizedURI](3L), uriSeq = SequenceNumber[NormalizedURI](3L), version = ModelVersion[DenseLDA](1), numOfWords = 200, feature = Some(LDATopicFeature(Array(1f, 0f))), state = URILDATopicStates.ACTIVE))
          libRepo.save(CortexLibrary(libraryId = Id[Library](1), ownerId = Id[User](1), kind = LibraryKind.USER_CREATED, state = State[CortexLibrary]("active"), seq = SequenceNumber[CortexLibrary](1)))
          libRepo.save(CortexLibrary(libraryId = Id[Library](1), ownerId = Id[User](1), kind = LibraryKind.SYSTEM_SECRET, state = State[CortexLibrary]("active"), seq = SequenceNumber[CortexLibrary](2)))
        }

        updater.update()

        db.readOnlyReplica { implicit s =>
          libLDARepo.all().size === 1
          val model = libLDARepo.getActiveByLibraryId(Id[Library](1), uriRep.version).get
          model.topic.get.value.toList === List(0.1f, 0.5f, 0.4f)
          model.firstTopicScore === Some(0.5f)
          model.firstTopic === Some(LDATopic(1))
          model.secondTopic === Some(LDATopic(2))
          model.thirdTopic === Some(LDATopic(0))
          (math.abs(model.entropy.get - 1.36) < 0.01) === true

          libLDARepo.getActiveByLibraryId(Id[Library](2), uriRep.version) === None // no feature for SYSTEM CREATED library
          commitRepo.getByModelAndVersion(StatModelName.LDA_LIBRARY, uriRep.version.version).get.seq === 3
        }
      }
    }
  }
}
