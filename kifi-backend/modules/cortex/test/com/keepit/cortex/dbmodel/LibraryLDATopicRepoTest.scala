package com.keepit.cortex.dbmodel

import com.keepit.common.db.{ SequenceNumber, Id }
import com.keepit.common.time._
import com.keepit.cortex.CortexTestInjector
import com.keepit.cortex.core.ModelVersion
import com.keepit.cortex.models.lda.{ LDATopic, DenseLDA }
import com.keepit.model._
import org.joda.time.DateTime
import org.specs2.mutable.Specification

class LibraryLDATopicRepoTest extends Specification with CortexTestInjector {
  "library lda topic repo" should {
    "retrieve user's library features" in {
      withDb() { implicit injector =>
        val libTopicRepo = inject[LibraryLDATopicRepo]
        val libMemRepo = inject[CortexLibraryMembershipRepo]

        val libTopic1 = LibraryLDATopic(libraryId = Id[Library](1), version = ModelVersion[DenseLDA](1), state = LibraryLDATopicStates.ACTIVE, numOfEvidence = 10, topic = Some(LibraryTopicMean(Array(1f, 0f)))) // user 1 follow this
        val libTopic2 = LibraryLDATopic(libraryId = Id[Library](2), version = ModelVersion[DenseLDA](1), state = LibraryLDATopicStates.ACTIVE, numOfEvidence = 10, topic = Some(LibraryTopicMean(Array(0.9f, 0.1f)))) // user 1 no longer follows this
        val libTopic3 = LibraryLDATopic(libraryId = Id[Library](3), version = ModelVersion[DenseLDA](1), state = LibraryLDATopicStates.ACTIVE, numOfEvidence = 10, topic = Some(LibraryTopicMean(Array(0.8f, 0.2f)))) // user 1 follows this
        val libTopic4 = LibraryLDATopic(libraryId = Id[Library](4), version = ModelVersion[DenseLDA](1), state = LibraryLDATopicStates.ACTIVE, numOfEvidence = 10, topic = Some(LibraryTopicMean(Array(0.7f, 0.3f)))) // user 2 follows this
        val libTopic5 = LibraryLDATopic(libraryId = Id[Library](5), version = ModelVersion[DenseLDA](1), state = LibraryLDATopicStates.NOT_APPLICABLE, numOfEvidence = 10, topic = Some(LibraryTopicMean(Array(0.6f, 0.4f)))) // user 1 follows this

        val time = new DateTime(2014, 7, 20, 21, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)

        val libMem1 = CortexLibraryMembership(membershipId = Id[LibraryMembership](1), libraryId = Id[Library](1), userId = Id[User](1), memberSince = time, access = LibraryAccess.READ_WRITE, state = LibraryMembershipStates.ACTIVE, seq = SequenceNumber[LibraryMembership](1))
        val libMem2 = CortexLibraryMembership(membershipId = Id[LibraryMembership](2), libraryId = Id[Library](2), userId = Id[User](1), memberSince = time, access = LibraryAccess.READ_WRITE, state = LibraryMembershipStates.INACTIVE, seq = SequenceNumber[LibraryMembership](2))
        val libMem3 = CortexLibraryMembership(membershipId = Id[LibraryMembership](3), libraryId = Id[Library](3), userId = Id[User](1), memberSince = time, access = LibraryAccess.READ_WRITE, state = LibraryMembershipStates.ACTIVE, seq = SequenceNumber[LibraryMembership](3))
        val libMem4 = CortexLibraryMembership(membershipId = Id[LibraryMembership](4), libraryId = Id[Library](4), userId = Id[User](2), memberSince = time, access = LibraryAccess.READ_WRITE, state = LibraryMembershipStates.ACTIVE, seq = SequenceNumber[LibraryMembership](4))
        val libMem5 = CortexLibraryMembership(membershipId = Id[LibraryMembership](5), libraryId = Id[Library](5), userId = Id[User](1), memberSince = time, access = LibraryAccess.READ_WRITE, state = LibraryMembershipStates.ACTIVE, seq = SequenceNumber[LibraryMembership](5))

        db.readWrite { implicit s =>
          List(libTopic1, libTopic2, libTopic3, libTopic4, libTopic5) foreach { libTopicRepo.save(_) }
          List(libMem1, libMem2, libMem3, libMem4, libMem5) foreach { libMemRepo.save(_) }
        }

        db.readOnlyReplica { implicit s =>
          var feats = libTopicRepo.getUserFollowedLibraryFeatures(Id[User](1), ModelVersion[DenseLDA](1))
          feats.map { _.value.toList }.toSet === Set(List(1f, 0f), List(0.8f, 0.2f))

          feats = libTopicRepo.getUserFollowedLibraryFeatures(Id[User](2), ModelVersion[DenseLDA](1))
          feats.map { _.value }.flatten.toList === List(0.7f, 0.3f)
        }

      }
    }

    "retrieve libraries by topic ids" in {
      withDb() { implicit injector =>
        val libTopicRepo = inject[LibraryLDATopicRepo]
        val libTopic1 = LibraryLDATopic(libraryId = Id[Library](1), version = ModelVersion[DenseLDA](1), state = LibraryLDATopicStates.ACTIVE, numOfEvidence = 10, firstTopic = Some(LDATopic(0)), secondTopic = Some(LDATopic(1)), thirdTopic = Some(LDATopic(2)), topic = None)
        val libTopic2 = LibraryLDATopic(libraryId = Id[Library](2), version = ModelVersion[DenseLDA](1), state = LibraryLDATopicStates.ACTIVE, numOfEvidence = 10, firstTopic = Some(LDATopic(0)), secondTopic = Some(LDATopic(1)), thirdTopic = Some(LDATopic(2)), topic = None)
        val libTopic3 = LibraryLDATopic(libraryId = Id[Library](3), version = ModelVersion[DenseLDA](1), state = LibraryLDATopicStates.ACTIVE, numOfEvidence = 10, firstTopic = Some(LDATopic(0)), secondTopic = Some(LDATopic(1)), thirdTopic = Some(LDATopic(3)), topic = None)
        val libTopic4 = LibraryLDATopic(libraryId = Id[Library](4), version = ModelVersion[DenseLDA](1), state = LibraryLDATopicStates.ACTIVE, numOfEvidence = 10, firstTopic = Some(LDATopic(0)), secondTopic = Some(LDATopic(2)), thirdTopic = Some(LDATopic(3)), topic = None)

        db.readWrite { implicit s =>
          List(libTopic1, libTopic2, libTopic3, libTopic4) foreach { libTopicRepo.save(_) }
        }

        val (first, second, third) = (LDATopic(0), LDATopic(1), LDATopic(2))

        db.readOnlyReplica { implicit s =>
          libTopicRepo.getLibraryByTopics(first, Some(second), Some(third), limit = 10, version = ModelVersion[DenseLDA](1)).sortBy(_.libraryId).map(_.libraryId.id) === List(1, 2)
          libTopicRepo.getLibraryByTopics(first, Some(second), limit = 10, version = ModelVersion[DenseLDA](1)).sortBy(_.libraryId).map(_.libraryId.id) === List(1, 2, 3)
          libTopicRepo.getLibraryByTopics(first, limit = 10, version = ModelVersion[DenseLDA](1)).sortBy(_.libraryId).map(_.libraryId.id) === List(1, 2, 3, 4)
        }

      }
    }
  }
}
