package com.keepit.model

import org.specs2.mutable.Specification
import com.keepit.test.{ ShoeboxApplicationInjector, ShoeboxApplication }
import com.keepit.common.db.Id
import play.api.test.Helpers._

class PageInfoTest extends Specification with ShoeboxApplicationInjector {
  "PageInfoRepo" should {
    "save and retrieve page info" in {
      running(new ShoeboxApplication()) {
        val pageInfoRepo = inject[PageInfoRepo]
        db.readWrite { implicit session =>
          val saved = pageInfoRepo.save(PageInfo(uriId = Id(1)))
          pageInfoRepo.get(saved.id.get).uriId === Id(1)
        }
      }
    }
  }
}
