package com.keepit.search

import com.keepit.common.db.slick.Database
import com.keepit.inject._
import com.keepit.model.{UserRepo, User}
import com.keepit.test.{DbRepos, EmptyApplication}
import org.specs2.mutable.SpecificationWithJUnit
import play.api.Play.current
import play.api.test.Helpers._

class SearchConfigTest extends SpecificationWithJUnit with DbRepos {
  "The search configuration" should {
    "load defaults correctly" in {
      running(new EmptyApplication()) {
        val searchConfigManager =
          new SearchConfigManager(None, inject[SearchConfigExperimentRepo], inject[Database])
        val userRepo = inject[UserRepo]
        inject[Database].readWrite { implicit s =>
          val andrew = userRepo.save(User(firstName = "Andrew", lastName = "Connor"))
          val greg = userRepo.save(User(firstName = "Greg", lastName = "Metvin"))
          val (c1, _) = searchConfigManager.getConfig(andrew.id.get, "fortytwo")
          val (c2, _) = searchConfigManager.getConfig(greg.id.get, "fortytwo")
          c1 === c2
          c1 === SearchConfig(SearchConfig.defaultParams)
        }
      }
    }
    "load overrides for experiments" in {
      running(new EmptyApplication()) {
        val searchConfigManager =
          new SearchConfigManager(None, inject[SearchConfigExperimentRepo], inject[Database])
        val userRepo = inject[UserRepo]
        inject[Database].readWrite { implicit s =>
          val andrew = userRepo.save(User(firstName = "Andrew", lastName = "Connor"))
          val v1 = searchConfigManager.saveExperiment(SearchConfigExperiment(config = SearchConfig(
            "recencyBoost" -> "2.0",
            "percentMatch" -> "70",
            "tailCutting" -> "0.30"
          ), weight = 0.5, state = SearchConfigExperimentStates.ACTIVE))
          val v2 = searchConfigManager.saveExperiment(SearchConfigExperiment(config = SearchConfig(
            "recencyBoost" -> "1.0",
            "percentMatch" -> "90",
            "tailCutting" -> "0.10"
          ), weight = 0.5, state = SearchConfigExperimentStates.ACTIVE))
          val (c1, e1) = searchConfigManager.getConfig(andrew.id.get, "andrew conner")
          val (c2, e2) = searchConfigManager.getConfig(andrew.id.get, "Andrew  Conner")
          c1 === c2
          Seq(70, 90) must contain(c1.asInt("percentMatch"))
          Seq(2.0, 1.0) must contain(c1.asDouble("recencyBoost"))
          Seq(0.30, 0.10) must contain(c1.asDouble("tailCutting"))

          c1.asInt("percentMatch") match {
            case 70 => e1.get === v1.id.get
            case 90 => e2.get === v2.id.get
          }
        }
      }
    }
    "load correct override based on weights" in {
      running(new EmptyApplication()) {
        val searchConfigManager = new SearchConfigManager(None, inject[SearchConfigExperimentRepo], inject[Database])
        val userRepo = inject[UserRepo]
        inject[Database].readWrite { implicit s =>
          val andrew = userRepo.save(User(firstName = "Andrew", lastName = "Connor"))
          searchConfigManager.saveExperiment(SearchConfigExperiment(config = SearchConfig(
            "recencyBoost" -> "2.0",
            "percentMatch" -> "70",
            "tailCutting" -> "0.30"
          ), weight = 0, state = SearchConfigExperimentStates.ACTIVE))
          searchConfigManager.saveExperiment(SearchConfigExperiment(config = SearchConfig(
            "recencyBoost" -> "1.0",
            "percentMatch" -> "90",
            "tailCutting" -> "0.10"
          ), weight = 1000, state = SearchConfigExperimentStates.ACTIVE))

          val (c1, _) = searchConfigManager.getConfig(andrew.id.get, "andrew conner")
          val (c2, _) = searchConfigManager.getConfig(andrew.id.get, "software engineer")
          val (c3, _) = searchConfigManager.getConfig(andrew.id.get, "fortytwo inc")
          c1 === c2
          c1 === c3
          c1.asDouble("recencyBoost") === 1.0
          c1.asDouble("percentMatch") === 90
          c1.asDouble("tailCutting") === 0.1
        }
      }
    }
    "not get configs from inactive experiments" in {
      running(new EmptyApplication()) {
        val searchConfigManager =
          new SearchConfigManager(None, inject[SearchConfigExperimentRepo], inject[Database])
        val userRepo = inject[UserRepo]
        inject[Database].readWrite { implicit s =>
          val greg = userRepo.save(User(firstName = "Greg", lastName = "Metvin"))
          val ex = searchConfigManager.saveExperiment(SearchConfigExperiment(config = SearchConfig(
            "percentMatch" -> "700",
            "phraseBoost" -> "500.0"
          ), weight = 1, state = SearchConfigExperimentStates.ACTIVE))

          val (c1, _) = searchConfigManager.getConfig(greg.id.get, "turtles")
          c1.asInt("percentMatch") === 700
          c1.asDouble("phraseBoost") === 500.0

          searchConfigManager.saveExperiment(ex.withState(SearchConfigExperimentStates.INACTIVE))

          val (c2, _) = searchConfigManager.getConfig(greg.id.get, "turtles")
          c2.asInt("percentMatch") !== 700
          c2.asDouble("phraseBoost") !== 500.0
        }
      }
    }
    "update startedAt in experiments when started" in {
      val exp = new SearchConfigExperiment()
      val startedExp = exp.withState(SearchConfigExperimentStates.ACTIVE)
      startedExp.startedAt must beSome
    }
  }
}
