package com.keepit.classify

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

import java.util.concurrent.TimeUnit

import org.specs2.execute.SkipException
import org.specs2.mutable._

import com.keepit.common.db.slick.Database
import com.keepit.inject._
import com.keepit.test._

import akka.actor.ActorSystem
import akka.testkit.TestKit
import play.api.Play.current
import play.api.test.Helpers._

class DomainTagImporterTest extends TestKit(ActorSystem()) with Specification {

  private def await[T](f: Future[T]): T = {
    Await.result(f, pairIntToDuration(100, TimeUnit.MILLISECONDS))
  }

  "The domain tag importer" should {
    "load domain sensitivity from a map of tags to domains" in {
      running(new DevApplication().withFakePersistEvent().withTestActorSystem(system)) {
        val db = inject[Database]
        val tagRepo = inject[DomainTagRepo]
        val domainTagImporter = inject[DomainTagImporterImpl]
        val domainRepo = inject[DomainRepo]
        db.readWrite { implicit s =>
          // add some existing tags
          tagRepo.save(DomainTag(name = DomainTagName("t1"), sensitive = Option(false)))
          tagRepo.save(DomainTag(name = DomainTagName("t2"), sensitive = Option(false)))
          tagRepo.save(DomainTag(name = DomainTagName("t3"), sensitive = Option(true)))
        }

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
        ) foreach await

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
      running(new DevApplication().withFakePersistEvent().withTestActorSystem(system)) {
        val tagRepo = inject[DomainTagRepo]
        val domainRepo = inject[DomainRepo]
        val db = inject[Database]
        val domainTagImporter = inject[DomainTagImporterImpl]
        db.readWrite { implicit s =>
          tagRepo.save(DomainTag(name = DomainTagName("t1"), sensitive = Some(false)))
          tagRepo.save(DomainTag(name = DomainTagName("t2"), sensitive = Some(false)))
          tagRepo.save(DomainTag(name = DomainTagName("t3"), sensitive = Some(true)))
        }

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
        ) foreach await

        db.readWrite { implicit s =>
          Seq("apple.com", "amazon.com", "google.com", "methvin.net", "42go.com")
              .map(domainRepo.get(_).get).map(inject[SensitivityUpdater].calculateSensitivity)
        }

        db.readOnly { implicit s =>
          domainRepo.get("apple.com").get.sensitive === Some(false)
          domainRepo.get("amazon.com").get.sensitive === Some(false)
          domainRepo.get("google.com").get.sensitive === Some(false)
          domainRepo.get("methvin.net").get.sensitive === None
          domainRepo.get("42go.com").get.sensitive === None
        }
      }
    }
    "respect manual overrides" in {
      running(new DevApplication().withFakePersistEvent().withTestActorSystem(system)) {
        val tagRepo = inject[DomainTagRepo]
        val domainRepo = inject[DomainRepo]
        val db = inject[Database]
        val domainTagImporter = inject[DomainTagImporterImpl]

        db.readWrite { implicit s =>
          tagRepo.save(DomainTag(name = DomainTagName("things"), sensitive = Some(false)))
          tagRepo.save(DomainTag(name = DomainTagName("stuff"), sensitive = None))
        }

        await(domainTagImporter.applyTagToDomains(
            DomainTagName("things"), Set("cnn.com", "yahoo.com", "google.com").toSeq))
        db.readWrite { implicit s =>
          Seq("cnn.com", "yahoo.com", "google.com")
            .map(domainRepo.get(_).get).map(inject[SensitivityUpdater].calculateSensitivity)

          domainRepo.get("cnn.com").get.sensitive === Some(false)
          domainRepo.save(domainRepo.get("cnn.com").get.withManualSensitive(Some(true)))
          domainRepo.get("cnn.com").get.sensitive === Some(true)
        }

        await(domainTagImporter.applyTagToDomains(
            DomainTagName("stuff"), Set("apple.com", "microsoft.com", "cnn.com").toSeq))
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
