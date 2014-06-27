package com.keepit.cortex.dbmodel

import org.specs2.mutable.Specification
import com.keepit.cortex.CortexTestInjector
import com.keepit.cortex.core.StatModelName

class FeatureCommitInfoRepoTest extends Specification with CortexTestInjector{
  "commit info repo" should {
    "work" in {
      withDb() { implicit s =>

        val repo = inject[FeatureCommitInfoRepo]

        db.readWrite{ implicit s =>
          repo.getByModelAndVersion(StatModelName("foo"), version = 1) === None
          repo.save(FeatureCommitInfo(id = None, modelName = StatModelName("lda"), modelVersion = 1, seq = 100L))
          var commit = repo.getByModelAndVersion(StatModelName("lda"), 1)
          commit.get.seq === 100L

          repo.save(commit.get.withSeq(200L))
          commit = repo.getByModelAndVersion(StatModelName("lda"), 1)
          commit.get.seq === 200L

          commit = repo.getByModelAndVersion(StatModelName("lda"), 2)
          commit === None
        }
      }
    }
  }
}
