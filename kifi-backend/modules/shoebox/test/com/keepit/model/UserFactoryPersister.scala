package com.keepit.model

import com.google.inject.Injector
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.model.UserFactory.PartialUser

object UserFactoryHelper {

  implicit class Persister(partialUser: PartialUser) {
    def save(implicit injector: Injector, session: RWSession): User = {
      injector.getInstance(classOf[UserRepo]).save(partialUser.get.copy(id = None))
    }
  }
}