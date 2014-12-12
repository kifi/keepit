package com.keepit.model

import com.google.inject.Injector
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.model.UserConnectionFactory.PartialUserConnection

object UserConnectionFactoryHelper {

  implicit class UserConnectionPersister(partialUserConnection: PartialUserConnection) {
    def saved(implicit injector: Injector, session: RWSession): UserConnection = {
      injector.getInstance(classOf[UserConnectionRepo]).save(partialUserConnection.get.copy(id = None))
    }
  }

  implicit class UserConnectionsPersister(partialUserConnections: Seq[PartialUserConnection]) {
    def saved(implicit injector: Injector, session: RWSession): Seq[UserConnection] = {
      val repo = injector.getInstance(classOf[UserConnectionRepo])
      partialUserConnections.map(u => repo.save(u.get.copy(id = None)))
    }
  }
}
