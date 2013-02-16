package com.keepit.classify

import org.joda.time.DateTime
import org.specs2.mutable._

import com.keepit.common.db.slick.DBConnection
import com.keepit.inject._
import com.keepit.test._

import akka.actor.ActorSystem
import scala.concurrent.{Await, Future}
import play.api.libs.concurrent.Execution.Implicits._
import scala.concurrent.duration._
import play.api.Play.current
import play.api.test.Helpers._
import java.util.concurrent.TimeUnit


class DomainTagImporterTest extends SpecificationWithJUnit with DbRepos {
  val system = ActorSystem("system")
  val settings = DomainTagImportSettings()
  "The domain tag importer" should {
    "load domain sensitivity from a map of tags to domains" in {
      running(new EmptyApplication()) {
        val tagRepo = inject[DomainTagRepo]
        val domainRepo = inject[DomainRepo]
        val domainToTagRepo = inject[DomainToTagRepo]
        val db = inject[DBConnection]
        val domainTagImporter = new DomainTagImporterImpl(domainRepo, tagRepo, domainToTagRepo,
          inject[SensitivityUpdater], provide(new DateTime), system,  db, settings)
        db.readWrite { implicit s =>
          // add some existing tags
          tagRepo.save(DomainTag(name = DomainTagName("t1"), sensitive = Option(false)))
          tagRepo.save(DomainTag(name = DomainTagName("t2"), sensitive = Option(false)))
          tagRepo.save(DomainTag(name = DomainTagName("t3"), sensitive = Option(true)))

          Seq(
            domainTagImporter.applyTagToDomains(
              DomainTagName("t1"), Seq("cnn.com", "yahoo.com", "google.com")),
            domainTagImporter.applyTagToDomains(
              DomainTagName("t2"), Seq("cnn.com", "amazon.com", "apple.com")),
            domainTagImporter.applyTagToDomains(
              DomainTagName("t3"), Seq("apple.com", "42go.com", "methvin.net")),
            // add a new tag (unknown sensitivity)
            domainTagImporter.applyTagToDomains(
              DomainTagName("t4"), Seq("42go.com", "amazon.com", "wikipedia.org"))
          ).foreach { future =>
            Await.result(future, pairIntToDuration((100, TimeUnit.MILLISECONDS)))
          }
        }

        db.readOnly { implicit s =>
          domainRepo.get("apple.com").get.sensitive === Some(true)
          domainRepo.get("amazon.com").get.sensitive === None
          domainRepo.get("google.com").get.sensitive === Some(false)
          domainRepo.get("methvin.net").get.sensitive === Some(true)
          domainRepo.get("42go.com").get.sensitive === Some(true)
          domainRepo.get("wikipedia.org").get.sensitive === None
        }
      }
    }
    "properly remove domain tags" in {
      running(new EmptyApplication()) {
        val tagRepo = inject[DomainTagRepo]
        val domainRepo = inject[DomainRepo]
        val domainToTagRepo = inject[DomainToTagRepo]
        val db = inject[DBConnection]
        val domainTagImporter = new DomainTagImporterImpl(domainRepo, tagRepo, domainToTagRepo,
          inject[SensitivityUpdater], provide(new DateTime), system, db, settings)

        db.readWrite { implicit s =>
        // add some existing tags
          tagRepo.save(DomainTag(name = DomainTagName("t1"), sensitive = Some(false)))
          tagRepo.save(DomainTag(name = DomainTagName("t2"), sensitive = Some(false)))
          tagRepo.save(DomainTag(name = DomainTagName("t3"), sensitive = Some(true)))

          Seq(
            domainTagImporter.applyTagToDomains(
              DomainTagName("t1"), Seq("cnn.com", "yahoo.com", "google.com")),
            domainTagImporter.applyTagToDomains(
              DomainTagName("t2"), Seq("cnn.com", "amazon.com", "apple.com")),
            domainTagImporter.applyTagToDomains(
              DomainTagName("t2"), Seq("cnn.com", "amazon.com", "apple.com")),
            domainTagImporter.applyTagToDomains(
              DomainTagName("t3"), Seq("apple.com", "42go.com", "methvin.net")),
            // remove a tag
            domainTagImporter.removeTag(DomainTagName("t3"))
          ).foreach { future =>
            Await.result(future, pairIntToDuration((100, TimeUnit.MILLISECONDS)))
          }
        }

        db.readOnly { implicit s =>
          domainRepo.get("apple.com").get.sensitive === Some(false)
          domainRepo.get("amazon.com").get.sensitive === Some(false)
          domainRepo.get("google.com").get.sensitive === Some(false)
          domainRepo.get("methvin.net").get.sensitive === Some(false)
          domainRepo.get("42go.com").get.sensitive === Some(false)
        }
      }
    }
    "respect manual overrides" in {
      running(new EmptyApplication()) {
        val tagRepo = inject[DomainTagRepo]
        val domainRepo = inject[DomainRepo]
        val domainToTagRepo = inject[DomainToTagRepo]
        val db = inject[DBConnection]
        val domainTagImporter = new DomainTagImporterImpl(domainRepo, tagRepo, domainToTagRepo,
          inject[SensitivityUpdater], provide(new DateTime), system, db, settings)

        db.readWrite { implicit s =>
          tagRepo.save(DomainTag(name = DomainTagName("things"), sensitive = Some(false)))
          tagRepo.save(DomainTag(name = DomainTagName("stuff"), sensitive = None))

          Await.result(domainTagImporter.applyTagToDomains(
            DomainTagName("things"), Set("cnn.com", "yahoo.com", "google.com")), pairIntToDuration(100, TimeUnit.MILLISECONDS))

          domainRepo.get("cnn.com").get.sensitive === Some(false)
          domainRepo.save(domainRepo.get("cnn.com").get.withManualSensitive(Some(true)))
          domainRepo.get("cnn.com").get.sensitive === Some(true)

          Await.result(domainTagImporter.applyTagToDomains(
            DomainTagName("stuff"), Set("apple.com", "microsoft.com", "cnn.com")), pairIntToDuration(100, TimeUnit.MILLISECONDS))

          domainRepo.get("cnn.com").get.sensitive === Some(true)
          domainRepo.save(domainRepo.get("cnn.com").get.withManualSensitive(None))
          domainRepo.get("cnn.com").get.sensitive === None
        }
      }
    }
  }
}
