package com.keepit.model

import org.specs2.mutable._
import com.keepit.common.db.slick._
import com.keepit.test.ShoeboxTestInjector
import com.keepit.common.db.Id
import play.api.libs.json.Json
import com.keepit.common.math.{ Probability, ProbabilityDensity }
import com.keepit.model.UserFactoryHelper._

class UserExperimentTest extends Specification with ShoeboxTestInjector {

  "UserExperiment" should {

    "load by user and experiment type" in {
      withDb() { implicit injector =>
        val userRepo = inject[UserRepo]
        val expRepo = inject[UserExperimentRepo]

        val (shanee, santa) = inject[Database].readWrite { implicit session =>
          (UserFactory.user().withName("Shanee", "Smith").withUsername("test").saved,
            UserFactory.user().withName("Santa", "Claus").withUsername("test2").saved)
        }

        inject[Database].readWrite { implicit session =>
          expRepo.save(UserExperiment(userId = shanee.id.get, experimentType = ExperimentType.ADMIN))
        }

        inject[Database].readOnlyMaster { implicit session =>
          expRepo.get(shanee.id.get, ExperimentType.ADMIN) must beSome
          expRepo.get(shanee.id.get, ExperimentType.FAKE) must beNone
          expRepo.get(santa.id.get, ExperimentType.ADMIN) must beNone
          expRepo.get(santa.id.get, ExperimentType.FAKE) must beNone
        }
      }
    }

    "persist" in {
      withDb() { implicit injector =>
        val userRepo = inject[UserRepo]
        val expRepo = inject[UserExperimentRepo]

        val (shanee, shachaf, santa) = inject[Database].readWrite { implicit session =>
          (UserFactory.user().withName("Shanee", "Smith").withUsername("test").saved,
            UserFactory.user().withName("Shachaf", "Smith").withUsername("test3").saved,
            UserFactory.user().withName("Santa", "Claus").withUsername("test2").saved)
        }

        inject[Database].readWrite { implicit session =>
          expRepo.save(UserExperiment(userId = shanee.id.get, experimentType = ExperimentType.ADMIN))
          expRepo.save(UserExperiment(userId = santa.id.get, experimentType = ExperimentType.ADMIN))
          expRepo.save(UserExperiment(userId = santa.id.get, experimentType = ExperimentType.FAKE))
        }

        inject[Database].readWrite { implicit session =>
          val shanees = expRepo.getUserExperiments(shanee.id.get)
          shanees.size === 2
          shanees.head === ExperimentType.ADMIN
          val santas = expRepo.getUserExperiments(santa.id.get)
          santas.size === 3
          val shachafs = expRepo.getUserExperiments(shachaf.id.get)
          shachafs.size === 1
          val admins = expRepo.getByType(ExperimentType.ADMIN)
          admins.size === 2
          val fakes = expRepo.getByType(ExperimentType.FAKE)
          fakes.size === 1
          fakes.head.userId === santa.id.get
        }
      }
    }
  }

  "ProbabilisticExperimentGenerator" should {

    val firstExp = ExperimentType("first")
    val secondExp = ExperimentType("second")
    val thirdExp = ExperimentType("third")
    val conditionExp = ExperimentType("condition")
    val salt = "pepper"

    val firstDensity = ProbabilityDensity(Seq(
      Probability(firstExp, 0.2),
      Probability(secondExp, 0.7),
      Probability(thirdExp, 0.1)
    ))

    val secondDensity = ProbabilityDensity(Seq(
      Probability(firstExp, 0.2),
      Probability(secondExp, 0.3),
      Probability(thirdExp, 0.3)
    ))

    val firstGen = ProbabilisticExperimentGenerator(name = Name("first test generator"), condition = Some(conditionExp), salt = salt, density = firstDensity)
    val secondGen = ProbabilisticExperimentGenerator(name = Name("second test generator"), condition = None, salt = salt, density = secondDensity)

    "be serializable" in {
      Json.toJson(firstGen).as[ProbabilisticExperimentGenerator] === firstGen
      Json.toJson(secondGen).as[ProbabilisticExperimentGenerator] === secondGen
    }

    "be persisted and cached" in {
      withDb() { implicit injector =>
        import com.keepit.common.cache.TransactionalCaching.Implicits.directCacheAccess
        val repo = inject[ProbabilisticExperimentGeneratorRepo]
        val cache = inject[ProbabilisticExperimentGeneratorAllCache]

        val (savedFirstGen, savedSecondGen) = db.readWrite { implicit session => (repo.save(firstGen), repo.save(secondGen)) }

        val allGen = db.readOnlyMaster { implicit session => repo.allActive() }
        allGen === Seq(savedFirstGen, savedSecondGen)
        cache.get(ProbabilisticExperimentGeneratorAllKey) === Some(allGen)

        db.readOnlyMaster { implicit session => repo.getByName(savedFirstGen.name) } === Some(savedFirstGen)
      }
    }

    "generate correct experiments" in {
      val userId = Id[User](42)
      val expectedHash = 0.8132363287297162

      firstGen.hash(userId) === expectedHash
      firstGen(userId, Set.empty) === None
      firstGen(userId, Set(conditionExp)) === Some(secondExp)

      secondGen.hash(userId) === expectedHash
      secondGen(userId, Set.empty) === None
      secondGen(userId, Set(conditionExp)) === None
    }
  }

  "probability density" should {
    "work using binary search or linear search" in {
      val n = 47
      val p = 1.0 / n
      val eps = 1.0 / (2 * n)
      val density = ProbabilityDensity((0 until n - 1).map { i => Probability(i, p) })
      (0 until n - 1).map { i =>
        val x = eps + i * p
        density.linearSample(x).get === i
        density.binarySample(x).get === i
      }
      density.linearSample(1.0 - eps) === None
      density.binarySample(1.0 - eps) === None
    }

    "work in edge cases" in {
      var density = ProbabilityDensity(Seq(Probability(1, 0.5)))
      density.binarySample(0.7) === None
      density.binarySample(0.3) === Some(1)

      density = ProbabilityDensity(Seq())
      density.binarySample(0.2) === None
    }
  }
}
