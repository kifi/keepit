package com.keepit.cortex.dbmodel

import com.keepit.cortex.CortexTestInjector
import com.keepit.cortex.core.ModelVersion
import com.keepit.cortex.models.lda.DenseLDA
import org.specs2.mutable.Specification

class LDAInfoRepoTest extends Specification with CortexTestInjector {
  "lda info repo" should {

    val version = ModelVersion[DenseLDA](1)
    val info1 = LDAInfo(version = version, topicId = 0, dimension = 4, numOfDocs = 10)
    val info2 = LDAInfo(version = version, topicId = 1, dimension = 4, numOfDocs = 20, topicName = "music")
    val info3 = LDAInfo(version = version, topicId = 1, dimension = 4, numOfDocs = 30)
    val info4 = LDAInfo(version = version, topicId = 1, dimension = 4, numOfDocs = 40, isNameable = false)

    "query dimension" in {
      withDb() { implicit injector =>
        val repo = inject[LDAInfoRepo]
        db.readWrite { implicit s =>
          List(info1, info2, info3, info4).foreach { repo.save(_) }
          repo.getDimension(version).get === 4
        }
      }
    }

    "query unamed topics" in {
      withDb() { implicit injector =>
        val repo = inject[LDAInfoRepo]
        db.readWrite { implicit s =>
          List(info1, info2, info3, info4).foreach { repo.save(_) }
          repo.getUnamed(version, 2).map { _.numOfDocs } === List(30, 10)
        }
      }
    }
  }
}
