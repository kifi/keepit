package com.keepit.commanders


import com.keepit.model.{ExperimentType, User}
import com.keepit.common.db.Id
import com.keepit.shoebox.ShoeboxServiceClient

import com.google.inject.Inject

import scala.concurrent.Future

import play.api.libs.concurrent.Execution.Implicits.defaultContext


class RemoteUserExperimentCommander @Inject() (shoebox: ShoeboxServiceClient) extends UserExperimentCommander {

  def getExperimentsByUser(userId: Id[User]): Future[Set[ExperimentType]] = {
    shoebox.getUserExperiments(userId).map{ experimentSeq => addDynamicExperiments(experimentSeq.toSet) }
  }

}

