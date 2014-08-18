package com.keepit.cortex.dbmodel

import org.specs2.mutable.Specification
import com.keepit.test.DbInjectionHelper
import com.keepit.cortex.CortexTestInjector
import org.joda.time.DateTime
import com.keepit.common.time._
import com.keepit.common.db._
import com.keepit.common.db.Id
import com.keepit.model._
import com.keepit.common.db.State
import com.keepit.common.db.SequenceNumber
import com.keepit.cortex.core.ModelVersion
import com.keepit.cortex.models.lda._
import com.keepit.common.time._

class URILDATopicRepoTest extends Specification with CortexTestInjector {
  "uri lda repo" should {
    "persist and retrieve feature" in {
      withDb() { implicit injector =>
        val uriTopicRepo = inject[URILDATopicRepo]
        val feat = URILDATopic(
          uriId = Id[NormalizedURI](1),
          numOfWords = 100,
          firstTopic = Some(LDATopic(2)),
          secondTopic = Some(LDATopic(1)),
          thirdTopic = None,
          firstTopicScore = Some(0.5f),
          sparseFeature = Some(SparseTopicRepresentation(dimension = 4, topics = Map(LDATopic(2) -> 0.5f, LDATopic(1) -> 0.3f))),
          feature = Some(LDATopicFeature(Array(0.3f, 0.5f, 0.1f, 0.1f))),
          version = ModelVersion[DenseLDA](1),
          uriSeq = SequenceNumber[NormalizedURI](1),
          state = URILDATopicStates.ACTIVE
        )

        val feat2 = URILDATopic(uriId = Id[NormalizedURI](2), version = ModelVersion[DenseLDA](1), numOfWords = 0, uriSeq = SequenceNumber[NormalizedURI](2), state = URILDATopicStates.NOT_APPLICABLE)

        db.readWrite { implicit s =>
          uriTopicRepo.save(feat);
          uriTopicRepo.save(feat2)
        }

        db.readOnlyMaster { implicit s =>
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

          uriTopicRepo.getFirstTopicAndScore(Id[NormalizedURI](1), ModelVersion[DenseLDA](1)) === Some((LDATopic(2), 0.5f))

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
              numOfWords = 100,
              firstTopic = Some(LDATopic(2)),
              secondTopic = Some(LDATopic(1)),
              thirdTopic = None,
              sparseFeature = Some(SparseTopicRepresentation(dimension = 4, topics = Map(LDATopic(2) -> 0.5f, LDATopic(1) -> 0.3f))),
              feature = Some(LDATopicFeature(Array(0.3f, 0.5f, 0.1f, 0.1f))),
              version = ModelVersion[DenseLDA](1),
              uriSeq = SequenceNumber[NormalizedURI](i),
              state = URILDATopicStates.ACTIVE))
          }

          (6 to 10).map { i =>
            uriTopicRepo.save(URILDATopic(
              uriId = Id[NormalizedURI](i),
              numOfWords = 100,
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

        db.readOnlyMaster { implicit s =>
          uriTopicRepo.getHighestSeqNumber(ModelVersion[DenseLDA](1)).value === 5
          uriTopicRepo.getHighestSeqNumber(ModelVersion[DenseLDA](2)).value === 10
          uriTopicRepo.getHighestSeqNumber(ModelVersion[DenseLDA](3)).value === 0
        }

        db.readOnlyMaster { implicit s =>
          uriTopicRepo.getFeaturesSince(SequenceNumber[NormalizedURI](0), ModelVersion[DenseLDA](1), limit = 5).map { _.uriSeq.value } === List(1, 2, 3, 4, 5)
          uriTopicRepo.getFeaturesSince(SequenceNumber[NormalizedURI](7), ModelVersion[DenseLDA](2), limit = 5).map { _.uriSeq.value } === List(8, 9, 10)
          uriTopicRepo.getFeaturesSince(SequenceNumber[NormalizedURI](10), ModelVersion[DenseLDA](2), limit = 5).map { _.uriSeq.value } === List()
          uriTopicRepo.getFeaturesSince(SequenceNumber[NormalizedURI](0), ModelVersion[DenseLDA](3), limit = 5).map { _.uriSeq.value } === List()
        }

      }
    }
  }

  "query user's interests" in {
    withDb() { implicit injector =>

      val keepRepo = inject[CortexKeepRepo]
      val topicRepo = inject[URILDATopicRepo]

      val keeps = List(CortexKeep(
        id = None,
        createdAt = currentDateTime,
        updatedAt = currentDateTime,
        keptAt = new DateTime(2014, 7, 1, 21, 59, 0, 0, DEFAULT_DATE_TIME_ZONE),
        keepId = Id[Keep](1),
        userId = Id[User](1),
        uriId = Id[NormalizedURI](1),
        isPrivate = false,
        state = State[CortexKeep]("active"),
        source = KeepSource.bookmarkImport,
        seq = SequenceNumber[CortexKeep](1L)
      ),
        CortexKeep(
          id = None,
          createdAt = currentDateTime,
          updatedAt = currentDateTime,
          keptAt = new DateTime(2014, 7, 20, 21, 59, 0, 0, DEFAULT_DATE_TIME_ZONE),
          keepId = Id[Keep](2),
          userId = Id[User](1),
          uriId = Id[NormalizedURI](2),
          isPrivate = false,
          state = State[CortexKeep]("active"),
          source = KeepSource.bookmarkImport,
          seq = SequenceNumber[CortexKeep](2L)
        ),

        CortexKeep(
          id = None,
          createdAt = currentDateTime,
          updatedAt = currentDateTime,
          keptAt = new DateTime(2014, 7, 20, 21, 59, 0, 0, DEFAULT_DATE_TIME_ZONE),
          keepId = Id[Keep](3),
          userId = Id[User](1),
          uriId = Id[NormalizedURI](3),
          isPrivate = false,
          state = State[CortexKeep]("inactive"),
          source = KeepSource.bookmarkImport,
          seq = SequenceNumber[CortexKeep](3L)
        )
      )

      val topics = List(
        URILDATopic(
          id = None,
          uriId = Id[NormalizedURI](1),
          uriSeq = SequenceNumber[NormalizedURI](1L),
          version = ModelVersion[DenseLDA](1),
          numOfWords = 100,
          firstTopic = Some(LDATopic(1)),
          feature = Some(LDATopicFeature(Array(1f, 0f))),
          state = URILDATopicStates.ACTIVE
        ),
        URILDATopic(
          id = None,
          uriId = Id[NormalizedURI](2),
          uriSeq = SequenceNumber[NormalizedURI](2L),
          version = ModelVersion[DenseLDA](1),
          numOfWords = 100,
          firstTopic = Some(LDATopic(2)),
          feature = Some(LDATopicFeature(Array(0.5f, 0.5f))),
          state = URILDATopicStates.ACTIVE
        )
      )

      db.readWrite { implicit s =>
        keeps.foreach { keepRepo.save(_) }
        topics.foreach { topicRepo.save(_) }

        topicRepo.getUserTopicHistograms(Id[User](1), ModelVersion[DenseLDA](1)).toList === List((LDATopic(1), 1), (LDATopic(2), 1))
        topicRepo.getUserTopicHistograms(Id[User](2), ModelVersion[DenseLDA](1)).toList === List()
        topicRepo.getUserTopicHistograms(Id[User](1), ModelVersion[DenseLDA](1), after = Some(new DateTime(2014, 7, 10, 21, 59, 0, 0, DEFAULT_DATE_TIME_ZONE))).toList === List((LDATopic(2), 1))
        topicRepo.countUserURIFeatures(Id[User](1), ModelVersion[DenseLDA](1), 0) === 2
        topicRepo.getUserURIFeatures(Id[User](1), ModelVersion[DenseLDA](1), 0).map { _.value }.flatten === List(1f, 0f, 0.5f, 0.5f)

      }
    }
  }

  "get K latest uri ids" in {
    withDb() { implicit injector =>
      val uriTopicRepo = inject[URILDATopicRepo]
      val time = new DateTime(2013, 2, 14, 21, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)
      db.readWrite { implicit s =>
        (1 to 5).map { i =>
          uriTopicRepo.save(URILDATopic(
            uriId = Id[NormalizedURI](i),
            updatedAt = time.plusMinutes(i),
            numOfWords = 100,
            firstTopic = Some(LDATopic(2)),
            secondTopic = Some(LDATopic(1)),
            thirdTopic = None,
            sparseFeature = Some(SparseTopicRepresentation(dimension = 4, topics = Map(LDATopic(2) -> 0.5f, LDATopic(1) -> 0.3f))),
            feature = Some(LDATopicFeature(Array(0.3f, 0.5f, 0.1f, 0.1f))),
            version = ModelVersion[DenseLDA](1),
            uriSeq = SequenceNumber[NormalizedURI](i),
            state = URILDATopicStates.ACTIVE))
        }

        uriTopicRepo.getLatestURIsInTopic(LDATopic(2), ModelVersion[DenseLDA](1), 2).map { _.id } === List(5, 4)
        uriTopicRepo.getLatestURIsInTopic(LDATopic(2), ModelVersion[DenseLDA](1), 5).map { _.id } === List(5, 4, 3, 2, 1)
        uriTopicRepo.getLatestURIsInTopic(LDATopic(100), ModelVersion[DenseLDA](1), 2).map { _.id } === List()
        uriTopicRepo.getLatestURIsInTopic(LDATopic(2), ModelVersion[DenseLDA](100), 2).map { _.id } === List()
      }

    }
  }

  "query num docs in topics" in {
    withDb() { implicit injector =>
      val uriTopicRepo = inject[URILDATopicRepo]
      db.readWrite { implicit s =>
        (1 to 5).map { i =>
          uriTopicRepo.save(URILDATopic(
            uriId = Id[NormalizedURI](i),
            numOfWords = 100,
            firstTopic = Some(LDATopic(i)),
            version = ModelVersion[DenseLDA](1),
            uriSeq = SequenceNumber[NormalizedURI](i),
            state = URILDATopicStates.ACTIVE))
        }

        uriTopicRepo.getTopicCounts(ModelVersion[DenseLDA](1)).sortBy(_._1) === (1 to 5).map { i => (i, 1) }.toList

      }
    }
  }
}
