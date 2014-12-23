package com.keepit.model

import com.google.inject.Injector
import com.keepit.common.db.State
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.model.LibraryInviteFactory.PartialLibraryInvite
import com.keepit.model.LibraryMembershipFactory._
import com.keepit.model.LibraryMembershipFactoryHelper._

object LibraryInviteFactoryHelper {

  implicit class LibraryInvitePersister(partialLibraryInvite: PartialLibraryInvite) {
    def saved(implicit injector: Injector, session: RWSession): LibraryInvite = {
      injector.getInstance(classOf[LibraryInviteRepo]).save(partialLibraryInvite.get.copy(id = None))
    }
  }

  implicit class LibraryInviteStatePersister(libraryInvite: LibraryInvite) {
    def savedStateChange(state: State[LibraryInvite])(implicit injector: Injector, session: RWSession): LibraryInvite = {
      val res = injector.getInstance(classOf[LibraryInviteRepo]).save(libraryInvite.withState(state))
      state match {
        case LibraryInviteStates.ACCEPTED => membership().fromLibraryInvite(libraryInvite).saved
      }
      res
    }
  }

  implicit class LibraryInvitesPersister(partialLibraryInvites: Seq[PartialLibraryInvite]) {
    def saved(implicit injector: Injector, session: RWSession): Seq[LibraryInvite] = {
      val repo = injector.getInstance(classOf[LibraryInviteRepo])
      partialLibraryInvites.map(u => repo.save(u.get.copy(id = None)))
    }
  }
}
