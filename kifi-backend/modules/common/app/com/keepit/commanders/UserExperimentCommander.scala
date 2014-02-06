package com.keepit.commanders


import com.keepit.model.{ExperimentType, User}
import com.keepit.common.db.Id


trait UserExperimentCommander {

  def addDynamicExperiments(statics: Set[ExperimentType]): Set[ExperimentType] = {
    statics //logic for dynamic experiments goes here
  }

}
