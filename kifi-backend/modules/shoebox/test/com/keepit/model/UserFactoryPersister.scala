package com.keepit.model

import com.google.inject.Injector
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.model.UserFactory.PartialUser

object UserFactoryHelper {

  implicit class UserPersister(partialUser: PartialUser) {
    def saved(implicit injector: Injector, session: RWSession): User = {
      injector.getInstance(classOf[UserRepo]).save(partialUser.get.copy(id = None))
    }
  }

  implicit class UsersPersister(partialUsers: Seq[PartialUser]) {
    def saved(implicit injector: Injector, session: RWSession): Seq[User] = {
      val repo = injector.getInstance(classOf[UserRepo])
      partialUsers.map(u => repo.save(u.get.copy(id = None)))
    }
  }
}