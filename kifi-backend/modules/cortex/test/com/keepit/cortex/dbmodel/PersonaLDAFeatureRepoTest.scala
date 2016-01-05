package com.keepit.cortex.dbmodel

import com.keepit.common.db.Id
import com.keepit.cortex.CortexTestInjector
import com.keepit.cortex.core.ModelVersion
import com.keepit.cortex.models.lda.{ LDATopic, DenseLDA }
import com.keepit.model.Persona
import org.specs2.mutable.Specification

class PersonaLDAFeatureRepoTest extends Specification with CortexTestInjector {
  "persona feature repo" should {
    "work" in {
      withDb() { implicit injector =>
        val repo = inject[PersonaLDAFeatureRepo]
        val model = PersonaLDAFeature(personaId = Id[Persona](1), version = ModelVersion[DenseLDA](1), feature = UserTopicMean(Array(0.5f, 0.3f, 0.2f)), firstTopic = LDATopic(0), secondTopic = LDATopic(1), thirdTopic = LDATopic(2))
        db.readWrite { implicit s =>
          repo.save(model)
        }

        db.readOnlyReplica { implicit s =>
          repo.getPersonaFeature(Id[Persona](1), ModelVersion[DenseLDA](1)).get.feature.mean.toList === List(0.5f, 0.3f, 0.2f)
        }
      }
    }
  }
}
