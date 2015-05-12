package com.keepit.model

import com.google.inject.Injector
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.model.LibraryFactory.PartialLibrary
import com.keepit.model.LibraryMembershipFactory._
import com.keepit.model.LibraryMembershipFactoryHelper._
import com.keepit.model.LibraryInviteFactory._
import com.keepit.model.LibraryInviteFactoryHelper._

object LibraryFactoryHelper {

  implicit class LibraryPersister(partialLibrary: PartialLibrary) {
    def saved(implicit injector: Injector, session: RWSession): Library = {
      val library = injector.getInstance(classOf[LibraryRepo]).save(partialLibrary.get.copy(id = None))
      membership().withLibraryOwner(library).saved
      library
    }
  }

  implicit class LibraryOwnershipPersister(library: Library) {
    def savedFollowerMembership(followers: User*)(implicit injector: Injector, session: RWSession): Library = {
      followers foreach { follower => membership().withLibraryFollower(library, follower).saved }
      library
    }
    def savedCollaboratorMembership(collaborators: User*)(implicit injector: Injector, session: RWSession): Library = {
      collaborators foreach { collab => membership().withLibraryCollaborator(library, collab).saved }
      library
    }
  }

  implicit class LibraryInvitationPersister(library: Library) {
    def savedInvitation(invited: User*)(implicit injector: Injector, session: RWSession): Library = {
      invited foreach { user => invite().fromLibraryOwner(library).toUser(user).saved }
      library
    }
  }

  implicit class LibrarysPersister(partialLibrarys: Seq[PartialLibrary]) {
    def saved(implicit injector: Injector, session: RWSession): Seq[Library] = {
      val repo = injector.getInstance(classOf[LibraryRepo])
      partialLibrarys.map { u =>
        val library = repo.save(u.get.copy(id = None))
        membership().withLibraryOwner(library).saved
        library
      }
    }
  }
}
