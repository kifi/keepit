package com.keepit.commanders


import com.keepit.model.{ExperimentType, User}
import com.keepit.common.db.Id


trait UserExperimentCommander {

  protected def getStaticExperimentsByUser(userId: Id[User]): Set[ExperimentType]

  def getExperimentsByUser(userId: Id[User]): Set[ExperimentType] = {
    //logic for dynamic experiments goes here
    getStaticExperimentsByUser(userId)
  }

  def userHasExperiment(userId: Id[User], experiment: ExperimentType) = {
    getExperimentsByUser(userId).contains(experiment)
  }

}
