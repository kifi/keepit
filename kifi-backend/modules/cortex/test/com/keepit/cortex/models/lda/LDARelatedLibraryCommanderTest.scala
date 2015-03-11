package com.keepit.cortex.models.lda

import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.cortex.CortexTestInjector
import com.keepit.cortex.core.ModelVersion
import com.keepit.cortex.dbmodel._
import com.keepit.model.Library
import org.specs2.mutable.Specification

class LDARelatedLibraryCommanderTest extends Specification with CortexTestInjector {

  "lda related library commander" should {
    "work" in {
      withDb() { implicit injector =>

        val libTopicRepo = inject[LibraryLDATopicRepo]
        val relatedLibRepo = inject[LDARelatedLibraryRepo]
        // 1,2,3 forms a clique (6 directed edges). 4, 5 forms a second clique (2 directed edges)
        val lib1 = LibraryLDATopic(libraryId = Id[Library](1), version = ModelVersion[DenseLDA](1), numOfEvidence = 10, state = LibraryLDATopicStates.ACTIVE, topic = Some(LibraryTopicMean(Array(0.9f, 0.1f, 0f, 0f))))
        val lib2 = lib1.copy(libraryId = Id[Library](2), topic = Some(LibraryTopicMean(Array(0.9f, 0f, 0.1f, 0f))))
        val lib3 = lib1.copy(libraryId = Id[Library](3), topic = Some(LibraryTopicMean(Array(0.9f, 0f, 0f, 0.1f))))
        val lib4 = lib1.copy(libraryId = Id[Library](4), topic = Some(LibraryTopicMean(Array(0f, 0.9f, 0.1f, 0f))))
        val lib5 = lib1.copy(libraryId = Id[Library](5), topic = Some(LibraryTopicMean(Array(0f, 0.9f, 0f, 0.1f))))

        db.readWrite { implicit s =>
          List(lib1, lib2, lib3, lib4, lib5).foreach {
            libTopicRepo.save(_)
          }
        }

        val correctWeight = 0.9878f
        val eps = 1e-4

        // make sure nb list and weights are correct
        def checkConnection(sourceId: Id[Library])(implicit session: RSession) = {
          relatedLibRepo.getNeighborIdsAndWeights(sourceId = sourceId, ModelVersion[DenseLDA](1))
            .sortBy(_._1).map { case (id, weight) => (id.id, weight < correctWeight + eps && weight > correctWeight - eps) }
            .toList
        }

        val commander = new LDARelatedLibraryCommanderImpl(db, libTopicRepo, relatedLibRepo)
        commander.fullyUpdateMode === true
        commander.update(ModelVersion[DenseLDA](1))

        db.readOnlyReplica { implicit s =>

          relatedLibRepo.count === 8

          checkConnection(Id[Library](1)) === List((2, true), (3, true))
          checkConnection(Id[Library](2)) === List((1, true), (3, true))
          checkConnection(Id[Library](3)) === List((1, true), (2, true))
          checkConnection(Id[Library](4)) === List((5, true))
          checkConnection(Id[Library](5)) === List((4, true))

        }

        // makes 1 disconnected from 2, 3
        db.readWrite { implicit s =>
          val model = libTopicRepo.get(Id[LibraryLDATopic](1))
          model.libraryId.id === 1
          libTopicRepo.save(model.copy(topic = Some(LibraryTopicMean(Array(0f, 0f, 0.1f, 0.9f)))))
        }

        commander.fullyUpdateMode === false
        commander.fullUpdate(ModelVersion[DenseLDA](1)) // need full update here

        db.readOnlyReplica { implicit s =>

          checkConnection(Id[Library](1)) === List()
          checkConnection(Id[Library](2)) === List((3, true))
          checkConnection(Id[Library](3)) === List((2, true))
          checkConnection(Id[Library](4)) === List((5, true))
          checkConnection(Id[Library](5)) === List((4, true))

        }

        // make library 2 has inactive feature
        db.readWrite { implicit s =>
          val model = libTopicRepo.getActiveByLibraryId(Id[Library](2), ModelVersion[DenseLDA](1)).get
          libTopicRepo.save(model.copy(state = LibraryLDATopicStates.INACTIVE))
          val lib6 = lib1.copy(libraryId = Id[Library](6), topic = Some(LibraryTopicMean(Array(0f, 0.9f, 0f, 0.1f))))
          libTopicRepo.save(lib6)
        }
        commander.fullyUpdateMode === false
        commander.update(ModelVersion[DenseLDA](1)) // partial update

        db.readOnlyReplica { implicit s =>

          checkConnection(Id[Library](1)) === List()
          checkConnection(Id[Library](2)) === List()              // 2 is deactivated
          checkConnection(Id[Library](3)) === List()              // 2 is gone
          checkConnection(Id[Library](4)) === List((5, true))
          checkConnection(Id[Library](5)) === List((4, true))
          checkConnection(Id[Library](6)).map { x => x._1 }.toSet === Set(4, 5)     // 6 is new
        }

      }
    }
  }

}
