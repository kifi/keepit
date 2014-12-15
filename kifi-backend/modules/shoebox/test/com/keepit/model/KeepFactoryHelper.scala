package com.keepit.model

import com.google.inject.Injector
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.model.KeepFactory.PartialKeep
import org.apache.commons.lang3.RandomStringUtils.random

object KeepFactoryHelper {

  implicit class KeepPersister(partialKeep: PartialKeep) {
    def saved(implicit injector: Injector, session: RWSession): Keep = {
      val keep = {
        val candidate = partialKeep.get
        if (candidate.urlId.id < 0 && candidate.uriId.id < 0) {
          val uri = injector.getInstance(classOf[NormalizedURIRepo]).save(NormalizedURI.withHash(candidate.url, Some("${random(5)}")))
          val url = injector.getInstance(classOf[URLRepo]).save(URLFactory(url = uri.url, normalizedUriId = uri.id.get))
          candidate.copy(uriId = uri.id.get, urlId = url.id.get)
        } else candidate
      }
      injector.getInstance(classOf[KeepRepo]).save(keep.copy(id = None))
    }
  }

  implicit class KeepsPersister(partialKeeps: Seq[PartialKeep]) {
    def saved(implicit injector: Injector, session: RWSession): Seq[Keep] = {
      val repo = injector.getInstance(classOf[KeepRepo])
      partialKeeps.map(u => repo.save(u.get.copy(id = None)))
    }
  }
}
