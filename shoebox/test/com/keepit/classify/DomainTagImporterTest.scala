package com.keepit.classify

import org.joda.time.DateTime
import org.specs2.mutable._

import com.keepit.common.analytics.FakePersistEventPluginImpl
import com.keepit.common.db.slick.Database
import com.keepit.inject._
import com.keepit.test._

import com.keepit.common.time.Clock
import akka.actor.ActorSystem
import scala.concurrent.{Await, Future}
import play.api.libs.concurrent.Execution.Implicits._
import scala.concurrent.duration._
import play.api.Play.current
import play.api.test.Helpers._
import java.util.concurrent.TimeUnit
import org.specs2.execute.SkipException

//TODO(greg): unskip tests when we figure out what's broken here
class DomainTagImporterTest extends Specification {
  private val system = ActorSystem("system")
  private val settings = DomainTagImportSettings()
  private val timeout = pairIntToDuration((100, TimeUnit.MILLISECONDS))

  "The domain tag importer" should {
    "load domain sensitivity from a map of tags to domains" in {
      throw new SkipException(skipped)
      running(new EmptyApplication()) {
        val db = inject[Database]
        val tagRepo = inject[DomainTagRepo]
        val domainRepo = inject[DomainRepo]
        val domainToTagRepo = inject[DomainToTagRepo]
        val domainTagImporter = new DomainTagImporterImpl(domainRepo, tagRepo, domainToTagRepo,
          inject[SensitivityUpdater], inject[Clock], system, db,
          new FakePersistEventPluginImpl(system), settings)
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
          )
        }.foreach(Await.result(_, timeout))

        db.readWrite { implicit s =>
          Seq("apple.com", "amazon.com", "google.com", "methvin.net", "42go.com", "wikipedia.org")
            .map(domainRepo.get(_).get).map(inject[SensitivityUpdater].calculateSensitivity)
        }

        db.readOnly { implicit s =>
          domainRepo.get("apple.com").get.sensitive === Some(true)
          domainRepo.get("amazon.com").get.sensitive === Some(false)
          domainRepo.get("google.com").get.sensitive === Some(false)
          domainRepo.get("methvin.net").get.sensitive === Some(true)
          domainRepo.get("42go.com").get.sensitive === Some(true)
          domainRepo.get("wikipedia.org").get.sensitive === Some(false)
        }
      }
    }
    "properly remove domain tags" in {
      throw new SkipException(skipped)
      running(new EmptyApplication()) {
        val tagRepo = inject[DomainTagRepo]
        val domainRepo = inject[DomainRepo]
        val domainToTagRepo = inject[DomainToTagRepo]
        val db = inject[Database]
        val domainTagImporter = new DomainTagImporterImpl(domainRepo, tagRepo, domainToTagRepo,
          inject[SensitivityUpdater], inject[Clock], system, db,
          new FakePersistEventPluginImpl(system), settings)

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
          )
        }.foreach(Await.result(_, timeout))

        db.readWrite { implicit s =>
          Seq("apple.com", "amazon.com", "google.com", "methvin.net", "42go.com")
              .map(domainRepo.get(_).get).map(inject[SensitivityUpdater].calculateSensitivity)
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
      throw new SkipException(skipped)
      running(new EmptyApplication()) {
        val tagRepo = inject[DomainTagRepo]
        val domainRepo = inject[DomainRepo]
        val domainToTagRepo = inject[DomainToTagRepo]
        val db = inject[Database]
        val domainTagImporter = new DomainTagImporterImpl(domainRepo, tagRepo, domainToTagRepo,
          inject[SensitivityUpdater], inject[Clock], system, db,
          new FakePersistEventPluginImpl(system), settings)

        val future1 = db.readWrite { implicit s =>
          tagRepo.save(DomainTag(name = DomainTagName("things"), sensitive = Some(false)))
          tagRepo.save(DomainTag(name = DomainTagName("stuff"), sensitive = None))

          domainTagImporter.applyTagToDomains(
            DomainTagName("things"), Set("cnn.com", "yahoo.com", "google.com").toSeq)
        }
        Await.result(future1, timeout)
        val future2 = db.readWrite { implicit s =>
          Seq("cnn.com", "yahoo.com", "google.com")
            .map(domainRepo.get(_).get).map(inject[SensitivityUpdater].calculateSensitivity)

          domainRepo.get("cnn.com").get.sensitive === Some(false)
          domainRepo.save(domainRepo.get("cnn.com").get.withManualSensitive(Some(true)))
          domainRepo.get("cnn.com").get.sensitive === Some(true)

          domainTagImporter.applyTagToDomains(
            DomainTagName("stuff"), Set("apple.com", "microsoft.com", "cnn.com").toSeq)
        }
        Await.result(future2, timeout)
        db.readWrite { implicit s =>
          Seq("apple.com", "microsoft.com", "cnn.com")
              .map(domainRepo.get(_).get).map(inject[SensitivityUpdater].calculateSensitivity)

          domainRepo.get("cnn.com").get.sensitive === Some(true)
          domainRepo.save(domainRepo.get("cnn.com").get.withManualSensitive(None))
          domainRepo.get("cnn.com").get.sensitive === Some(false)
        }
      }
    }
  }
}
