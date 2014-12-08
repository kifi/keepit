package com.keepit.model

import com.google.inject.Injector
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.model.LibraryMembershipFactory.PartialLibraryMembership

object LibraryMembershipFactoryHelper {

  implicit class LibraryMembershipPersister(partialLibraryMembership: PartialLibraryMembership) {
    def saved(implicit injector: Injector, session: RWSession): LibraryMembership = {
      injector.getInstance(classOf[LibraryMembershipRepo]).save(partialLibraryMembership.get.copy(id = None))
    }
  }

  implicit class LibraryMembershipsPersister(partialLibraryMemberships: Seq[PartialLibraryMembership]) {
    def saved(implicit injector: Injector, session: RWSession): Seq[LibraryMembership] = {
      val repo = injector.getInstance(classOf[LibraryMembershipRepo])
      partialLibraryMemberships.map(u => repo.save(u.get.copy(id = None)))
    }
  }
}
