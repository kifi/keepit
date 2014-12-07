package com.keepit.model

import com.google.inject.Injector
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.model.LibraryFactory.PartialLibrary

object LibraryFactoryHelper {

  implicit class LibraryPersister(partialLibrary: PartialLibrary) {
    def saved(implicit injector: Injector, session: RWSession): Library = {
      injector.getInstance(classOf[LibraryRepo]).save(partialLibrary.get.copy(id = None))
    }
  }

  implicit class LibrarysPersister(partialLibrarys: Seq[PartialLibrary]) {
    def saved(implicit injector: Injector, session: RWSession): Seq[Library] = {
      val repo = injector.getInstance(classOf[LibraryRepo])
      partialLibrarys.map(u => repo.save(u.get.copy(id = None)))
    }
  }
}
