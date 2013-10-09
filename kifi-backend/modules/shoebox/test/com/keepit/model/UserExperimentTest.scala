package com.keepit.model

import org.specs2.mutable._
import com.keepit.common.db.slick._
import com.keepit.test.ShoeboxTestInjector

class UserExperimentTest extends Specification with ShoeboxTestInjector {

  "UserExperiment" should {

    "load by user and experiment type" in {
      withDb() { implicit injector =>
      val userRepo = inject[UserRepo]
        val expRepo = inject[UserExperimentRepo]

        val (shanee, santa) = inject[Database].readWrite{ implicit session =>
          (userRepo.save(User(firstName = "Shanee", lastName = "Smith")),
           userRepo.save(User(firstName = "Santa", lastName = "Claus")))
        }

        inject[Database].readWrite { implicit session =>
          expRepo.save(UserExperiment(userId = shanee.id.get, experimentType = ExperimentType.ADMIN))
        }

        inject[Database].readOnly { implicit session =>
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

        val (shanee, shachaf, santa) = inject[Database].readWrite{ implicit session =>
          (userRepo.save(User(firstName = "Shanee", lastName = "Smith")),
           userRepo.save(User(firstName = "Shachaf", lastName = "Smith")),
           userRepo.save(User(firstName = "Santa", lastName = "Claus")))
        }

        inject[Database].readWrite{ implicit session =>
          expRepo.save(UserExperiment(userId = shanee.id.get, experimentType = ExperimentType.ADMIN))
          expRepo.save(UserExperiment(userId = santa.id.get, experimentType = ExperimentType.ADMIN))
          expRepo.save(UserExperiment(userId = santa.id.get, experimentType = ExperimentType.FAKE))
        }

        inject[Database].readWrite{ implicit session =>
          val shanees = expRepo.getUserExperiments(shanee.id.get)
          shanees.size === 1
          shanees.head === ExperimentType.ADMIN
          val santas = expRepo.getUserExperiments(santa.id.get)
          santas.size === 2
          val shachafs = expRepo.getUserExperiments(shachaf.id.get)
          shachafs.size === 0
          val admins = expRepo.getByType(ExperimentType.ADMIN)
          admins.size === 2
          val fakes = expRepo.getByType(ExperimentType.FAKE)
          fakes.size === 1
          fakes.head.userId === santa.id.get
        }
      }
    }
  }

}
