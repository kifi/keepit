package com.keepit.rover.model

import com.keepit.common.db.Id
import com.keepit.model.NormalizedURI
import com.keepit.rover.article.{ YoutubeArticle, DefaultArticle, EmbedlyArticle }
import com.keepit.rover.test.RoverTestInjector
import org.specs2.mutable.Specification
import com.keepit.common.time._
import org.specs2.time.NoTimeConversions
import scala.concurrent.duration._

class ArticleInfoTest extends Specification with NoTimeConversions with RoverTestInjector {

  val firstUri: Id[NormalizedURI] = Id(25)
  val firstUrl = "http://www.mcsweeneys.net/articles/son-its-time-we-talk-about-where-start-ups-come-from"

  "ArticleInfoRepo" should {

    "intern article infos" in {
      withDb() { implicit injector =>
        db.readWrite { implicit session =>
          articleInfoRepo.internByUri(firstUri, firstUrl, Set(EmbedlyArticle, DefaultArticle))
        }

        db.readOnlyMaster { implicit session =>
          val infos = articleInfoRepo.getByUri(firstUri)
          infos.size === 2
          infos.map(_.articleKind) === Set(EmbedlyArticle, DefaultArticle)
          infos.map(_.uriId) === Set(firstUri)
          infos.map(_.url) === Set(firstUrl)
        }

        db.readWrite { implicit session =>
          articleInfoRepo.internByUri(firstUri, firstUrl, Set(YoutubeArticle, DefaultArticle))
        }

        db.readOnlyMaster { implicit session =>
          val infos = articleInfoRepo.getByUri(firstUri)
          infos.size === 3
          infos.map(_.articleKind) === Set(EmbedlyArticle, DefaultArticle, YoutubeArticle)
          infos.map(_.uriId) === Set(firstUri)
          infos.map(_.url) === Set(firstUrl)
        }
      }
    }

    "get articles infos for image processing" in {
      withDb() { implicit injector =>
        val (embedlyArticle, defaultArticle) = db.readWrite { implicit session =>
          val now = clock.now()
          val articlesByKind = articleInfoRepo.internByUri(firstUri, firstUrl, Set(EmbedlyArticle, DefaultArticle))
          val embedlyArticle = articleInfoRepo.save(articlesByKind(EmbedlyArticle).copy(imageProcessingRequestedAt = Some(now.minusHours(2))))
          val defaultArticle = articleInfoRepo.save(articlesByKind(DefaultArticle).copy(imageProcessingRequestedAt = Some(now.minusHours(4)), lastImageProcessingAt = Some(now.minusHours(2))))
          (embedlyArticle, defaultArticle)
        }

        db.readOnlyMaster { implicit session =>
          articleInfoRepo.getRipeForImageProcessing(limit = 10, requestedForMoreThan = 3 hours, imageProcessingForMoreThan = 1 day) === Seq()
          articleInfoRepo.getRipeForImageProcessing(limit = 10, requestedForMoreThan = 3 hours, imageProcessingForMoreThan = 1 hour) === Seq(defaultArticle)
          articleInfoRepo.getRipeForImageProcessing(limit = 10, requestedForMoreThan = 1 hours, imageProcessingForMoreThan = 1 day) === Seq(embedlyArticle)
          articleInfoRepo.getRipeForImageProcessing(limit = 10, requestedForMoreThan = 1 hours, imageProcessingForMoreThan = 1 hour).toSet === Set(defaultArticle, embedlyArticle)
        }
      }
    }
  }
}
