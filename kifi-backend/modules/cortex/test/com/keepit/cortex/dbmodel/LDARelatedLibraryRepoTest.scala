package com.keepit.cortex.dbmodel

import com.keepit.common.db.Id
import com.keepit.cortex.CortexTestInjector
import com.keepit.cortex.core.ModelVersion
import com.keepit.cortex.models.lda.DenseLDA
import com.keepit.model.Library
import org.specs2.mutable.Specification

class LDARelatedLibraryRepoTest extends Specification with CortexTestInjector {
  "lda related library repo" should {
    "work" in {
      withDb() { implicit s =>
        val repo = inject[LDARelatedLibraryRepo]
        val model = LDARelatedLibrary(version = ModelVersion[DenseLDA](1), sourceId = Id[Library](1), destId = Id[Library](2), weight = 0.9f)
        val models = List(model, model.copy(destId = Id[Library](3), weight = 0.7f), model.copy(destId = Id[Library](4), weight = 0.5f))

        db.readWrite { implicit s =>
          models.foreach { repo.save(_) }
        }

        db.readOnlyReplica { implicit s =>
          repo.getTopNeighborIdsAndWeights(Id[Library](1), ModelVersion[DenseLDA](1), 1).map { case (id, w) => (id.id, w) } === List((2, 0.9f))
          repo.getTopNeighborIdsAndWeights(Id[Library](1), ModelVersion[DenseLDA](1), 2).map { case (id, w) => (id.id, w) } === List((2, 0.9f), (3, 0.7f))
          repo.getNeighborIdsAndWeights(Id[Library](1), ModelVersion[DenseLDA](1)).map { case (id, w) => (id.id, w) } === List((2, 0.9f), (3, 0.7f), (4, 0.5f))
        }
      }
    }
  }
}
