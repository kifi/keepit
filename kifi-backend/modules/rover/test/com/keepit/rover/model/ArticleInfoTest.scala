package com.keepit.rover.model

import com.keepit.rover.article.DefaultArticle
import com.keepit.rover.test.{ RoverApplication, RoverApplicationInjector }
import org.specs2.mutable.Specification
import com.keepit.common.db.Id
import play.api.test.Helpers._

class ArticleInfoTest extends Specification with RoverApplicationInjector {
  "ArticleInfoRepo" should {
    "save and retrieve ArticleInfo" in {
      running(new RoverApplication()) {
        val articleInfoRepo = inject[ArticleInfoRepo]
        db.readWrite { implicit session =>
          val saved = articleInfoRepo.save(ArticleInfo(uriId = Id(1), url = "http://www.lemonde.fr", kind = DefaultArticle.typeCode))
          articleInfoRepo.get(saved.id.get).uriId === Id(1)
        }
      }
    }
  }
}
