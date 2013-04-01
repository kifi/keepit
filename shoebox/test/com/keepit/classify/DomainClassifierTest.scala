package com.keepit.classify

import org.joda.time.DateTime
import org.specs2.mutable.Specification

import com.keepit.common.analytics.FakePersistEventPluginImpl
import com.keepit.common.db.slick.Database
import com.keepit.common.net.FakeHttpClient
import com.keepit.common.time.Clock
import com.keepit.inject.{provide, inject}
import com.keepit.test.{DbRepos, EmptyApplication}

import akka.actor.ActorSystem
import scala.concurrent.Await
import play.api.Play.current
import play.api.test.Helpers.running

import scala.concurrent.duration._
import java.util.concurrent.TimeUnit
import org.specs2.execute.SkipException

class DomainClassifierTest extends Specification with DbRepos {
  val system = ActorSystem("system")
  "The domain classifier" should {
    "use imported classifications and not fetch for known domains" in {
      throw new SkipException(skipped)
      running(new EmptyApplication()) {
        //val client = new HttpClientImpl()
        val client = new FakeHttpClient(Some(Map.empty))

        val classifier = new DomainClassifierImpl(system, inject[Database], client,
          inject[SensitivityUpdater], inject[DomainRepo], inject[DomainTagRepo], inject[DomainToTagRepo])
        val tagRepo = inject[DomainTagRepo]
        val domainRepo = inject[DomainRepo]
        val domainToTagRepo = inject[DomainToTagRepo]
        val importer = new DomainTagImporterImpl(domainRepo, tagRepo, domainToTagRepo,
          inject[SensitivityUpdater], inject[Clock], system, db,
          new FakePersistEventPluginImpl(system), DomainTagImportSettings())
        inject[Database].readWrite { implicit s =>
          tagRepo.save(DomainTag(name = DomainTagName("Search Engines"), sensitive = Some(false)))
          tagRepo.save(DomainTag(name = DomainTagName("Technology and computers"), sensitive = Some(false)))
          tagRepo.save(DomainTag(name = DomainTagName("Porn"), sensitive = Some(true)))

          Seq(
            importer.applyTagToDomains(DomainTagName("search engines"), Seq("google.com", "yahoo.com")),
            importer.applyTagToDomains(DomainTagName("Technology and Computers"), Seq("42go.com", "google.com")),
            importer.applyTagToDomains(DomainTagName("Porn"), Seq("playboy.com"))
          )
        }.foreach { Await.result(_, pairIntToDuration(100, TimeUnit.SECONDS) ) }

        classifier.isSensitive("google.com") === Right(false)
        classifier.isSensitive("yahoo.com") === Right(false)
        classifier.isSensitive("42go.com") === Right(false)
        classifier.isSensitive("playboy.com") === Right(true)
      }
    }
    "fetch if necessary" in {
      throw new SkipException(skipped)
      running(new EmptyApplication()) {
        val client = new FakeHttpClient(Some({
          case s if s.contains("yahoo.com") => "FR~Search engines"
          case s if s.contains("zdnet.com") => "FM~Technology and computers,News and magazines"
          case s if s.contains("schwab.com") => "FM~Business and services,Finance (Banks, Real estate, Insurance)"
          case s if s.contains("hover.com") => "FM~Business and services,Web hosting"
          case s if s.contains("42go.com") || s.contains("addepar.com") => "FM~Technology and computers"
          case s if s.contains("playboy.com") || s.contains("porn.com") => "FM~Porn"
        }))
        val classifier = new DomainClassifierImpl(system, inject[Database], client,
          inject[SensitivityUpdater], inject[DomainRepo], inject[DomainTagRepo], inject[DomainToTagRepo])
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
        ).foreach { future =>
          Await.result(future, pairIntToDuration(100, TimeUnit.MILLISECONDS))
        }

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
