package com.keepit.cortex.core

import com.google.inject.Inject
import com.keepit.commanders.RemoteUserExperimentCommander
import com.keepit.common.db.Id
import com.keepit.common.logging.Logging
import com.keepit.cortex.models.lda.DenseLDA
import com.keepit.model.{ ExperimentType, User }
import com.keepit.cortex.ModelVersions
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.Future

class CortexVersionCommander @Inject() (userExpCmdr: RemoteUserExperimentCommander) extends Logging {
  private val newModelExp = ExperimentType.CORTEX_NEW_MODEL

  private def userIsOnNewModel(userId: Id[User]): Future[Boolean] = {
    userExpCmdr.getExperimentsByUser(userId).map { expSet => expSet.contains(newModelExp) }
  }

  def getLDAVersionForUser(userId: Id[User]): Future[ModelVersion[DenseLDA]] = {
    if (ModelVersions.experimentalLDAVersion.isEmpty) {
      Future.successful(ModelVersions.denseLDAVersion)
    } else {
      userIsOnNewModel(userId).map { useNewModel =>
        if (useNewModel) {
          val v = ModelVersions.experimentalLDAVersion.head
          log.info(s"using experimental LDA version ${v} for user ${userId}")
          v
        } else ModelVersions.denseLDAVersion
      }
    }
  }
}
