package com.keepit.cortex.core

import com.google.inject.Inject
import com.keepit.commanders.RemoteUserExperimentCommander
import com.keepit.common.db.Id
import com.keepit.cortex.models.lda.DenseLDA
import com.keepit.model.{ ExperimentType, User }
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.Future

// Helpful for A/B testing
class CortexVersionCommander @Inject() (userExpCmdr: RemoteUserExperimentCommander) {
  private val newModelExp = ExperimentType.CORTEX_NEW_MODEL

  private def userIsOnNewModel(userId: Id[User]): Future[Boolean] = {
    userExpCmdr.getExperimentsByUser(userId).map { expSet => expSet.contains(newModelExp) }
  }

  // return an optional experimenting version. If none, cortex side defaults to a stable version.
  // This reduces the risk of passing a wrong version number.
  def getExperimentalLDAVersionForUser(userId: Id[User]): Future[Option[ModelVersion[DenseLDA]]] = {
    userIsOnNewModel(userId).map { useNewModel =>
      if (useNewModel) Some(ModelVersion[DenseLDA](2))
      else None
    }
  }
}
