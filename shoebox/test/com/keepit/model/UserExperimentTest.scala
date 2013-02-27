package com.keepit.model

import org.junit.runner.RunWith
import org.specs2.mutable._
import org.specs2.runner.JUnitRunner
import com.keepit.inject._
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import com.keepit.test.EmptyApplication
import com.keepit.common.social.SocialId
import com.keepit.common.social.SocialNetworks
import securesocial.core._
import play.api.Play.current
import play.api.test._
import play.api.test.Helpers._

@RunWith(classOf[JUnitRunner])
class UserExperimentTest extends SpecificationWithJUnit {

  "UserExperiment" should {

    "load by user and experiment type" in {
      running(new EmptyApplication()) {
        val userRepo = inject[UserRepo]
        val expRepo = inject[UserExperimentRepo]

        val (shanee, santa) = inject[Database].readWrite{ implicit session =>
          (userRepo.save(User(firstName = "Shanee", lastName = "Smith")),
           userRepo.save(User(firstName = "Santa", lastName = "Claus")))
        }

        inject[Database].readWrite{ implicit session =>
          expRepo.save(UserExperiment(userId = shanee.id.get, experimentType = ExperimentTypes.ADMIN))
        }

        inject[Database].readOnly{ implicit session =>
          expRepo.getExperiment(shanee.id.get, ExperimentTypes.ADMIN).isDefined === true
          expRepo.getExperiment(shanee.id.get, ExperimentTypes.FAKE).isDefined === false
          expRepo.getExperiment(santa.id.get, ExperimentTypes.ADMIN).isDefined === false
          expRepo.getExperiment(santa.id.get, ExperimentTypes.FAKE).isDefined === false
        }
      }
    }

    "persist" in {
      running(new EmptyApplication()) {
        val userRepo = inject[UserRepo]
        val expRepo = inject[UserExperimentRepo]

        val (shanee, shachaf, santa) = inject[Database].readWrite{ implicit session =>
          (userRepo.save(User(firstName = "Shanee", lastName = "Smith")),
           userRepo.save(User(firstName = "Shachaf", lastName = "Smith")),
           userRepo.save(User(firstName = "Santa", lastName = "Claus")))
        }

        inject[Database].readWrite{ implicit session =>
          expRepo.save(UserExperiment(userId = shanee.id.get, experimentType = ExperimentTypes.ADMIN))
          expRepo.save(UserExperiment(userId = santa.id.get, experimentType = ExperimentTypes.ADMIN))
          expRepo.save(UserExperiment(userId = santa.id.get, experimentType = ExperimentTypes.FAKE))
        }

        inject[Database].readWrite{ implicit session =>
          val shanees = expRepo.getByUser(shanee.id.get)
          shanees.size === 1
          shanees.head.experimentType === ExperimentTypes.ADMIN
          val santas = expRepo.getByUser(santa.id.get)
          santas.size === 2
          val shachafs = expRepo.getByUser(shachaf.id.get)
          shachafs.size === 0
          val admins = expRepo.getByType(ExperimentTypes.ADMIN)
          admins.size === 2
          val fakes = expRepo.getByType(ExperimentTypes.FAKE)
          fakes.size === 1
          fakes.head.userId === santa.id.get
        }
      }
    }
  }

}
