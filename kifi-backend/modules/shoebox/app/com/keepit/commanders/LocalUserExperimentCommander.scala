package com.keepit.commanders


import com.keepit.model.{ExperimentType, User, UserExperimentRepo, UserExperiment}
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database

import com.google.inject.Inject


class LocalUserExperimentCommander @Inject() (userExperimentRepo: UserExperimentRepo, db: Database) extends UserExperimentCommander  {

  protected def getStaticExperimentsByUser(userId: Id[User]): Set[ExperimentType] = {
    db.readOnly { implicit session => userExperimentRepo.getUserExperiments(userId) }
  }

  def addExperimentForUser(userId: Id[User], experiment: ExperimentType) = {
    db.readWrite { implicit session => userExperimentRepo.save(UserExperiment(userId = userId, experimentType = experiment)) }
  }

}
