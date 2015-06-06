package com.keepit.search

import com.keepit.common.akka.MonitoredAwait
import com.keepit.model.ExperimentType.NO_SEARCH_EXPERIMENTS
import com.keepit.test.CommonTestInjector
import org.specs2.mutable.Specification
import play.api.test.Helpers._
import com.keepit.model._
import com.keepit.shoebox.{ FakeShoeboxServiceModule, FakeShoeboxServiceClientImpl, ShoeboxServiceClient }
import com.google.inject.Injector
import com.keepit.common.usersegment.UserSegment
import scala.concurrent.Await
import scala.concurrent.duration._
import com.keepit.commanders.RemoteUserExperimentCommander
import com.keepit.common.db.Id
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.cache.{ HashMapMemoryCache, CacheStatistics }
import com.keepit.common.logging.{ AccessLog, Logging }

class SearchConfigTest extends Specification with CommonTestInjector {

  private def getUserExperiments(userId: Id[User])(implicit injector: Injector) = {
    val commander = new RemoteUserExperimentCommander(
      shoebox = inject[ShoeboxServiceClient],
      generatorCache = new ProbabilisticExperimentGeneratorAllCache(inject[CacheStatistics], inject[AccessLog], (new HashMapMemoryCache(), Duration.Inf)),
      monitoredAwait = inject[MonitoredAwait],
      airbrake = inject[AirbrakeNotifier])
    Await.result(commander.getExperimentsByUser(userId), Duration(1, "minutes")).toSet
  }

  "The search configuration" should {
    "load defaults correctly" in {
      withInjector(FakeShoeboxServiceModule()) { implicit injector: Injector =>
        val fakeShoeboxServiceClient = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]
        val searchConfigManager =
          new SearchConfigManager(None, inject[ShoeboxServiceClient], inject[MonitoredAwait])
        val Seq(andrew, greg) = fakeShoeboxServiceClient.saveUsers(
          UserFactory.user().withName("Andrew", "Conner").withUsername("test").get,
          UserFactory.user().withName("Greg", "Metvin").withUsername("test").get
        )

        val (c1, _) = searchConfigManager.getConfig(andrew.id.get, getUserExperiments(andrew.id.get))
        val (c2, _) = searchConfigManager.getConfig(greg.id.get, getUserExperiments(greg.id.get))
        c1 === c2
        c1 === SearchConfig(SearchConfig.defaultParams)
      }
    }
    "load overrides for experiments" in {
      withInjector(FakeShoeboxServiceModule()) { implicit injector: Injector =>
        val fakeShoeboxServiceClient = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]

        val searchConfigManager = new SearchConfigManager(None, inject[ShoeboxServiceClient], inject[MonitoredAwait])

        val Seq(andrew) = fakeShoeboxServiceClient.saveUsers(UserFactory.user().withName("Andrew", "Conner").withUsername("test").get)

        val v1 = Await.result(fakeShoeboxServiceClient.saveExperiment(SearchConfigExperiment(
          config = SearchConfig(
            "recencyBoost" -> "2.0",
            "percentMatch" -> "70",
            "tailCutting" -> "0.30"
          ), weight = 0.5, state = SearchConfigExperimentStates.ACTIVE
        )), Duration(1, "minutes"))

        val v2 = Await.result(fakeShoeboxServiceClient.saveExperiment(SearchConfigExperiment(config = SearchConfig(
          "recencyBoost" -> "1.0",
          "percentMatch" -> "90",
          "tailCutting" -> "0.10"
        ), weight = 0.5, state = SearchConfigExperimentStates.ACTIVE
        )), Duration(1, "minutes"))

        searchConfigManager.syncActiveExperiments
        val (c1, e1) = searchConfigManager.getConfig(andrew.id.get, Set(v1.experiment))
        Seq(70, 90) must contain(c1.asInt("percentMatch"))
        Seq(2.0, 1.0) must contain(c1.asDouble("recencyBoost"))
        Seq(0.30, 0.10) must contain(c1.asDouble("tailCutting"))
      }
    }
    "load correct override based on weights" in {
      withInjector(FakeShoeboxServiceModule()) { implicit injector: Injector =>
        val fakeShoeboxServiceClient = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]
        val searchConfigManager = new SearchConfigManager(None, inject[ShoeboxServiceClient], inject[MonitoredAwait])

        val Seq(andrew) = fakeShoeboxServiceClient.saveUsers(UserFactory.user().withName("Andrew", "Conner").withUsername("test").get)

        fakeShoeboxServiceClient.saveExperiment(SearchConfigExperiment(
          config = SearchConfig(
            "recencyBoost" -> "2.0",
            "percentMatch" -> "70",
            "tailCutting" -> "0.30"
          ), weight = 0.01, state = SearchConfigExperimentStates.ACTIVE
        ))

        fakeShoeboxServiceClient.saveExperiment(SearchConfigExperiment(config = SearchConfig(
          "recencyBoost" -> "1.0",
          "percentMatch" -> "90",
          "tailCutting" -> "0.10"
        ), weight = 0.99, state = SearchConfigExperimentStates.ACTIVE
        ))

        searchConfigManager.syncActiveExperiments

        val userExperiments = getUserExperiments(andrew.id.get)
        val (c1, _) = searchConfigManager.getConfig(andrew.id.get, userExperiments)
        val (c2, _) = searchConfigManager.getConfig(andrew.id.get, userExperiments)
        val (c3, _) = searchConfigManager.getConfig(andrew.id.get, userExperiments)
        c1 === c2
        c1 === c3
        c1.asDouble("recencyBoost") === 1.0
        c1.asDouble("percentMatch") === 90
        c1.asDouble("tailCutting") === 0.1
      }
    }
    "not get configs from inactive experiments" in {
      withInjector(FakeShoeboxServiceModule()) { implicit injector: Injector =>
        val fakeShoeboxServiceClient = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]
        val searchConfigManager = new SearchConfigManager(None, inject[ShoeboxServiceClient], inject[MonitoredAwait])

        val Seq(greg) = fakeShoeboxServiceClient.saveUsers(UserFactory.user().withName("Greg", "Methvin").withUsername("test").get)

        val ex = Await.result(fakeShoeboxServiceClient.saveExperiment(SearchConfigExperiment(config = SearchConfig(
          "percentMatch" -> "700",
          "phraseBoost" -> "500.0"
        ), weight = 1, state = SearchConfigExperimentStates.ACTIVE
        )), Duration(1, "minutes"))

        searchConfigManager.syncActiveExperiments
        val (c1, _) = searchConfigManager.getConfig(greg.id.get, getUserExperiments(greg.id.get))
        c1.asInt("percentMatch") === 700
        c1.asDouble("phraseBoost") === 500.0

        fakeShoeboxServiceClient.saveExperiment(ex.withState(SearchConfigExperimentStates.INACTIVE))

        searchConfigManager.syncActiveExperiments
        val (c2, _) = searchConfigManager.getConfig(greg.id.get, getUserExperiments(greg.id.get))
        c2.asInt("percentMatch") !== 700
        c2.asDouble("phraseBoost") !== 500.0
      }
    }
    "ignore experiments for users excluded from experiments" in {
      withInjector(FakeShoeboxServiceModule()) { implicit injector: Injector =>
        val fakeShoeboxServiceClient = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]
        val searchConfigManager = new SearchConfigManager(None, inject[ShoeboxServiceClient], inject[MonitoredAwait])

        val Seq(greg) = fakeShoeboxServiceClient.saveUsers(UserFactory.user().withName("Greg", "Methvin").withUsername("test").get)

        fakeShoeboxServiceClient.saveExperiment(SearchConfigExperiment(config = SearchConfig(
          "percentMatch" -> "9000",
          "phraseBoost" -> "10000.0"
        ), weight = 1, state = SearchConfigExperimentStates.ACTIVE
        ))

        searchConfigManager.syncActiveExperiments
        val (c1, _) = searchConfigManager.getConfig(greg.id.get, getUserExperiments(greg.id.get))
        c1.asInt("percentMatch") === 9000
        c1.asDouble("phraseBoost") === 10000.0

        fakeShoeboxServiceClient.saveUserExperiment(UserExperiment(userId = greg.id.get, experimentType = ExperimentType.NO_SEARCH_EXPERIMENTS))
        val (c2, _) = searchConfigManager.getConfig(greg.id.get, getUserExperiments(greg.id.get))
        c2.asInt("percentMatch") !== 9000
        c2.asDouble("phraseBoost") !== 10000.0
      }
    }
    "update startedAt in experiments when started" in {
      val exp = new SearchConfigExperiment()
      val startedExp = exp.withState(SearchConfigExperimentStates.ACTIVE)
      startedExp.startedAt must beSome
    }

    "config overriders should work" in {
      var conf = SearchConfig.byUserSegment(new UserSegment(-999999999))
      conf === SearchConfig.defaultConfig

      conf = SearchConfig.byUserSegment(new UserSegment(3))
      conf.asFloat("dampingHalfDecayFriends") === 2.5f
      conf.asInt("percentMatch") === 85
    }
  }
}
