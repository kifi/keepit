package com.keepit.model

import org.junit.runner.RunWith
import org.specs2.mutable._
import org.specs2.runner.JUnitRunner

import com.keepit.common.db.CX
import com.keepit.common.db.CX._
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

        val (shanee, santa) = CX.withConnection { implicit c =>
          (User(firstName = "Shanee", lastName = "Smith").save,
           User(firstName = "Santa", lastName = "Claus").save)
        }

        CX.withConnection { implicit c =>
          UserExperiment(userId = shanee.id.get, experimentType = ExperimentTypes.ADMIN).save
        }

        CX.withConnection { implicit c =>
          UserExperimentCxRepo.getExperiment(shanee.id.get, ExperimentTypes.ADMIN).isDefined === true
          UserExperimentCxRepo.getExperiment(shanee.id.get, ExperimentTypes.FAKE).isDefined === false
          UserExperimentCxRepo.getExperiment(santa.id.get, ExperimentTypes.ADMIN).isDefined === false
          UserExperimentCxRepo.getExperiment(santa.id.get, ExperimentTypes.FAKE).isDefined === false
        }
      }
    }

    "persist" in {
      running(new EmptyApplication()) {

        val (shanee, shachaf, santa) = CX.withConnection { implicit c =>
          (User(firstName = "Shanee", lastName = "Smith").save,
           User(firstName = "Shachaf", lastName = "Smith").save,
           User(firstName = "Santa", lastName = "Claus").save)
        }

        CX.withConnection { implicit c =>
          UserExperiment(userId = shanee.id.get, experimentType = ExperimentTypes.ADMIN).save
          UserExperiment(userId = santa.id.get, experimentType = ExperimentTypes.ADMIN).save
          UserExperiment(userId = santa.id.get, experimentType = ExperimentTypes.FAKE).save
        }

        CX.withConnection { implicit c =>
          val shanees = UserExperimentCxRepo.getByUser(shanee.id.get)
          shanees.size === 1
          shanees.head.experimentType === ExperimentTypes.ADMIN
          val santas = UserExperimentCxRepo.getByUser(santa.id.get)
          santas.size === 2
          val shachafs = UserExperimentCxRepo.getByUser(shachaf.id.get)
          shachafs.size === 0
          val admins = UserExperimentCxRepo.getByType(ExperimentTypes.ADMIN)
          admins.size === 2
          val fakes = UserExperimentCxRepo.getByType(ExperimentTypes.FAKE)
          fakes.size === 1
          fakes.head.userId === santa.id.get
        }
      }
    }
  }

}
