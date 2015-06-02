package com.keepit.rover.model

import com.keepit.common.store.ImagePath
import com.keepit.model._
import com.keepit.rover.article.{ EmbedlyArticle, DefaultArticle }
import com.keepit.rover.test.{ RoverApplication, RoverApplicationInjector }
import org.specs2.mutable.Specification
import com.keepit.common.db.{ SequenceNumber, Id }
import play.api.test.Helpers._
import com.keepit.common.time._

class RoverRepoTest extends Specification with RoverApplicationInjector {
  // This test uses an Application so that actual evolutions are applied and tested.
  "Rover" should {
    "save and retrieve models" in {
      running(new RoverApplication()) {

        // ArticleRepo
        val articleInfoRepo = inject[ArticleInfoRepo]
        db.readWrite { implicit session =>
          val saved = articleInfoRepo.save(RoverArticleInfo.initialize(uriId = Id(14), url = "http://www.lemonde.fr", kind = EmbedlyArticle))
          articleInfoRepo.get(saved.id.get).uriId.id === 14
        }

        // HttpProxyRepo
        val httpProxyRepo = inject[RoverHttpProxyRepo]
        db.readWrite { implicit session =>
          val saved = httpProxyRepo.save(RoverHttpProxy(alias = "marvin", host = "96.31.86.149", port = 3128, scheme = "http", username = None, password = None))
          httpProxyRepo.get(saved.id.get).alias === "marvin"
        }

        // SystemValueRepo
        val systemValueRepo = inject[SystemValueRepo]
        db.readWrite { implicit session =>
          val name = Name[SequenceNumber[NormalizedURI]]("normalized_uri_ingestion")
          systemValueRepo.getSequenceNumber(name) === None
          systemValueRepo.setSequenceNumber(name, SequenceNumber(42))
          systemValueRepo.getSequenceNumber(name) === Some(SequenceNumber(42))
        }

        // RoverImageInfoRepo
        val imageInfoRepo = inject[RoverImageInfoRepo]
        db.readWrite { implicit session =>
          val saved = imageInfoRepo.save(RoverImageInfo(
            format = ImageFormat.GIF,
            width = 100,
            height = 100,
            kind = ProcessImageOperation.Original,
            path = ImagePath("path"),
            source = ImageSource.RoverArticle(EmbedlyArticle),
            sourceImageHash = ImageHash("hash"),
            sourceImageUrl = None
          ))

          imageInfoRepo.get(saved.id.get).path.path === "path"
        }

        // ArticleImageRepo
        val articleImageRepo = inject[ArticleImageRepo]
        db.readWrite { implicit session =>
          val saved = articleImageRepo.save(ArticleImage(
            Id(14),
            EmbedlyArticle,
            ArticleVersionProvider.zero(EmbedlyArticle),
            imageUrl = "imageUrl",
            imageHash = ImageHash("hash")
          ))
          articleImageRepo.get(saved.id.get).articleKind === EmbedlyArticle
          articleImageRepo.get(saved.id.get).uriId.id === 14
        }
      }
    }
  }
}
