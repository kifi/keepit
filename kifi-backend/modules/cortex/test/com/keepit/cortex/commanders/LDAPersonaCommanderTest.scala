package com.keepit.cortex.commanders

import com.keepit.common.commanders.{ LDAPersonaCommanderImpl, LDAInfoCommander, LDAPersonaCommander }
import com.keepit.common.db.Id
import com.keepit.cortex.CortexTestInjector
import com.keepit.cortex.core.ModelVersion
import com.keepit.cortex.dbmodel._
import com.keepit.cortex.models.lda.{ LDATopic, DenseLDA }
import com.keepit.model.{ Persona, User }
import org.specs2.mutable.Specification

class LDAPersonaCommanderTest extends Specification with CortexTestInjector {

  "persona commander" should {
    "generate feature vector given topic ids" in {
      withDb() { implicit injector =>
        val personaRepo = inject[PersonaLDAFeatureRepo]
        val ldaUserRepo = inject[UserLDAStatsRepo]
        val commander = new LDAPersonaCommanderImpl(db, null, personaRepo, ldaUserRepo) {
          override def getLDADimension(implicit version: ModelVersion[DenseLDA]): Int = 4
        }

        implicit val version = ModelVersion[DenseLDA](1)

        val userLDA = UserLDAStats(userId = Id[User](1), version = version, numOfEvidence = 10, firstTopic = Some(LDATopic(0)), userTopicMean = Some(UserTopicMean(Array(0.8f, 0.2f, 0f, 0f))), userTopicVar = None)
        val userLDAs = List(
          userLDA,
          userLDA.copy(userId = Id[User](2), userTopicMean = Some(UserTopicMean(Array(0.6f, 0.4f, 0f, 0f)))),
          userLDA.copy(userId = Id[User](3), firstTopic = Some(LDATopic(2)), userTopicMean = Some(UserTopicMean(Array(0f, 0f, 0.6f, 0.4f))))
        )

        db.readWrite { implicit s =>
          userLDAs.foreach { ldaUserRepo.save(_) }
        }

        val (feature, sampleSize) = commander.generatePersonaFeature(Seq(LDATopic(0)))
        sampleSize === 2
        feature.mean.toList === List((0.8f + 0.6f) / 2, 0.3f, 0f, 0f)

        val (feature2, sampleSize2) = commander.generatePersonaFeature(Seq(LDATopic(0), LDATopic(2)))
        sampleSize2 === 3
        val expected = List((0.8f + 0.6f) / 3, 0.2f, 0.2f, 0.4f / 3)
        (feature2.mean.toList zip expected).forall { case (x, y) => math.abs(x - y) < 1e-4 } === true

        val (feature3, sampleSize3) = commander.generatePersonaFeature(Seq(LDATopic(100)))
        sampleSize3 === 0
        feature3.mean.toList === List(0f, 0f, 0f, 0f)

      }
    }

    "save feature vectors for persona" in {
      withDb() { implicit injector =>
        val personaRepo = inject[PersonaLDAFeatureRepo]
        val ldaUserRepo = inject[UserLDAStatsRepo]
        val commander = new LDAPersonaCommanderImpl(db, null, personaRepo, ldaUserRepo) {
          override def getLDADimension(implicit version: ModelVersion[DenseLDA]): Int = 4
        }

        implicit val version = ModelVersion[DenseLDA](1)

        commander.savePersonaFeature(Id[Persona](1), UserTopicMean(Array(0.6f, 0.4f, 0f, 0f)))

        db.readOnlyReplica { implicit s =>
          val model = personaRepo.getPersonaFeature(Id[Persona](1), version)
          model.get.feature.mean.toList === List(0.6f, 0.4f, 0f, 0f)
          model.get.firstTopic === LDATopic(0)
        }

        commander.savePersonaFeature(Id[Persona](1), UserTopicMean(Array(0.9f, 0.1f, 0f, 0f)))
        db.readOnlyReplica { implicit s =>
          val model = personaRepo.getPersonaFeature(Id[Persona](1), version)
          model.get.feature.mean.toList === List(0.9f, 0.1f, 0f, 0f)
          model.get.firstTopic === LDATopic(0)
        }

        1 === 1
      }
    }
  }

}
