package com.keepit.classify

import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration._

import java.util.concurrent.TimeUnit

import org.specs2.mutable.Specification

import com.keepit.common.db.slick.Database
import com.keepit.common.net.{HttpClient, FakeHttpClient}
import com.keepit.inject._
import com.keepit.test.{DevApplication, DbRepos}

import akka.actor.ActorSystem
import akka.testkit.TestKit
import play.api.Play.current
import play.api.test.Helpers.running

class DomainClassifierTest extends TestKit(ActorSystem()) with Specification with DbRepos {

  private def await[T](f: Future[T]): T = {
    Await.result(f, pairIntToDuration(100, TimeUnit.MILLISECONDS))
  }

  "The domain classifier" should {
    "use imported classifications and not fetch for known domains" in {
      running(new DevApplication().withFakeMail().withFakePersistEvent().withFakeHttpClient()
          .withTestActorSystem(system)) {
        val classifier = inject[DomainClassifierImpl]
        val tagRepo = inject[DomainTagRepo]
        val importer = inject[DomainTagImporterImpl]

        inject[Database].readWrite { implicit s =>
          tagRepo.save(DomainTag(name = DomainTagName("Search Engines"), sensitive = Some(false)))
          tagRepo.save(DomainTag(name = DomainTagName("Technology and computers"), sensitive = Some(false)))
          tagRepo.save(DomainTag(name = DomainTagName("Porn"), sensitive = Some(true)))
        }

        Seq(importer.applyTagToDomains(DomainTagName("search engines"), Seq("google.com", "yahoo.com")),
          importer.applyTagToDomains(DomainTagName("Technology and Computers"), Seq("42go.com", "google.com")),
          importer.applyTagToDomains(DomainTagName("Porn"), Seq("playboy.com"))
        ) foreach await

        classifier.isSensitive("google.com") === Right(false)
        classifier.isSensitive("yahoo.com") === Right(false)
        classifier.isSensitive("42go.com") === Right(false)
        classifier.isSensitive("playboy.com") === Right(true)
      }
    }
    "fetch if necessary" in {
      running(new DevApplication().withFakeMail().withFakePersistEvent()
          .withTestActorSystem(system)
          .overrideWith(new FortyTwoModule {
            override def configure() {
              bind[HttpClient].toInstance(new FakeHttpClient(Some({
                case s if s.contains("yahoo.com") => "FR~Search engines"
                case s if s.contains("zdnet.com") => "FM~Technology and computers,News and magazines"
                case s if s.contains("schwab.com") => "FM~Business and services,Finance (Banks, Real estate, Insurance)"
                case s if s.contains("hover.com") => "FM~Business and services,Web hosting"
                case s if s.contains("42go.com") || s.contains("addepar.com") => "FM~Technology and computers"
                case s if s.contains("playboy.com") || s.contains("porn.com") => "FM~Porn"
              })))
            }
          })) {
        val classifier = inject[DomainClassifierImpl]
        val domainRepo = inject[DomainRepo]
        val tagRepo = inject[DomainTagRepo]
        inject[Database].readWrite { implicit s =>
          tagRepo.save(DomainTag(name = DomainTagName("Search Engines"), sensitive = Some(false)))
          tagRepo.save(DomainTag(name = DomainTagName("Technology and computers"), sensitive = Some(false)))
          tagRepo.save(DomainTag(name = DomainTagName("Porn"), sensitive = Some(true)))
          tagRepo.save(
            DomainTag(name = DomainTagName("Finance (Banks, Real estate, Insurance)"), sensitive = Some(true)))

          domainRepo.save(Domain(hostname = "google.com", manualSensitive = Some(false)))
        }

        classifier.isSensitive("google.com") === Right(false)

        Seq(
          classifier.isSensitive("yahoo.com").left.get,
          classifier.isSensitive("zdnet.com").left.get,
          classifier.isSensitive("schwab.com").left.get,
          classifier.isSensitive("hover.com").left.get,
          classifier.isSensitive("42go.com").left.get,
          classifier.isSensitive("addepar.com").left.get,
          classifier.isSensitive("playboy.com").left.get,
          classifier.isSensitive("porn.com").left.get
        ) foreach await

        classifier.isSensitive("www.yahoo.com") === Right(false)
        classifier.isSensitive("yahoo.com") === Right(false)
        classifier.isSensitive("zdnet.com") === Right(false)
        classifier.isSensitive("schwab.com") === Right(true)
        classifier.isSensitive("hover.com") === Right(false)
        classifier.isSensitive("42go.com") === Right(false)
        classifier.isSensitive("addepar.com") === Right(false)
        classifier.isSensitive("playboy.com") === Right(true)
        classifier.isSensitive("www.porn.com") === Right(true)
        classifier.isSensitive("porn.com") === Right(true)

        classifier.isSensitive("local-domain") === Right(true)
      }
    }
  }
}
