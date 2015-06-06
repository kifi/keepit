package com.keepit.model

import com.keepit.model.UserConnectionFactoryHelper._
import com.google.inject.Injector
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.model.UserFactory.PartialUser

object UserFactoryHelper {

  implicit class UserPersister(partialUser: PartialUser) {
    def saved(implicit injector: Injector, session: RWSession): User = {
      val user = injector.getInstance(classOf[UserRepo]).save(partialUser.get.copy(id = None))
      if (partialUser.experiments.nonEmpty) {
        val experimentRepo = injector.getInstance(classOf[UserExperimentRepo])
        partialUser.experiments.foreach { experimentType =>
          experimentRepo.save(UserExperiment(userId = user.id.get, experimentType = experimentType))
        }
      }
      user
    }
  }

  implicit class UsersConnectionPersister(user1: User) {
    def savedConnection(user2: User)(implicit injector: Injector, session: RWSession): User = {
      UserConnectionFactory.connect().withUsers(user1, user2).saved
      user1
    }
  }

  implicit class UsersPersister(partialUsers: Seq[PartialUser]) {
    def saved(implicit injector: Injector, session: RWSession): Seq[User] = {
      partialUsers.map(u => new UserPersister(u).saved)
    }
  }
}
