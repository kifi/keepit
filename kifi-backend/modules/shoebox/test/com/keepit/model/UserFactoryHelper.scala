package com.keepit.model

import com.keepit.model.UserConnectionFactoryHelper._
import com.keepit.model.UserConnectionFactory._
import com.google.inject.Injector
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.model.UserFactory.PartialUser

object UserFactoryHelper {

  implicit class UserPersister(partialUser: PartialUser) {
    def saved(implicit injector: Injector, session: RWSession): User = {
      injector.getInstance(classOf[UserRepo]).save(partialUser.get.copy(id = None))
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
      val repo = injector.getInstance(classOf[UserRepo])
      partialUsers.map(u => repo.save(u.get.copy(id = None)))
    }
  }
}
