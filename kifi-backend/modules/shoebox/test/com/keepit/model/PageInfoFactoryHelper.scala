package com.keepit.model

import com.google.inject.Injector
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.model.PageInfoFactory.PartialPageInfo

object PageInfoFactoryHelper {

  implicit class PageInfoPersister(partialPageInfo: PartialPageInfo) {
    def saved(implicit injector: Injector, session: RWSession): PageInfo = {
      injector.getInstance(classOf[PageInfoRepo]).save(partialPageInfo.get.copy(id = None))
    }
  }

  implicit class PageInfosPersister(partialPageInfos: Seq[PartialPageInfo]) {
    def saved(implicit injector: Injector, session: RWSession): Seq[PageInfo] = {
      val repo = injector.getInstance(classOf[PageInfoRepo])
      partialPageInfos.map(u => repo.save(u.get.copy(id = None)))
    }
  }
}
