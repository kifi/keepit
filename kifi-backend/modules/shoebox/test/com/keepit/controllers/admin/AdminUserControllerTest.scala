package com.keepit.controllers.admin

import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.actor.FakeActorSystemModule
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.controller.FakeUserActionsModule
import com.keepit.common.crypto.FakeCryptoModule
import com.keepit.common.db.Id
import com.keepit.common.healthcheck.FakeAirbrakeModule
import com.keepit.common.mail.FakeMailModule
import com.keepit.common.social.{ FakeSocialGraphModule, FakeShoeboxAppSecureSocialModule }
import com.keepit.common.store.FakeShoeboxStoreModule
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.curator.FakeCuratorServiceClientModule
import com.keepit.heimdal.FakeHeimdalServiceClientModule
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.shoebox.FakeShoeboxServiceModule
import org.specs2.mutable.Specification
import com.keepit.test._
import com.keepit.model._
import com.google.inject.Injector
import com.keepit.common.net.FakeHttpClientModule
import play.libs.F.Tuple

class AdminUserControllerTest extends Specification with ShoeboxTestInjector {

  def modules = Seq(
    FakeShoeboxServiceModule(),
    FakeSocialGraphModule(),
    FakeABookServiceClientModule()
  )

  def setup()(implicit injector: Injector) = {

    val NON_ADMIN_ID = Id[User](87754) // cam's dad's id
    val ADMIN_ID = Id[User](35713) // cam's id
    val SUPERADMIN_ID = Id[User](1) // eishay's id

    val userRepo = inject[UserRepo]
    val userExperimentRepo = inject[UserExperimentRepo]
    db.readWrite { implicit session =>

      val userSuperAdminOpt: User = userRepo.save(User(
        firstName = "Clark", lastName = "Kent", username = Username("clark"), normalizedUsername = "clark"
      ))
      val userNonAdminOpt = userRepo.save(User(
        firstName = "Homer", lastName = "Simpson", username = Username("homer"), normalizedUsername = "homer"
      ))

      val dummySuperAdminOpt = userRepo.save(User(
        firstName = "Andrew", lastName = "Conner", username = Username("andrew"), normalizedUsername = "andrew"
      )) // need a dummy user saved, since Andrew is a superAdmin and his userId == 3.

      val userAdminOpt = userRepo.save(User(
        firstName = "Peter", lastName = "Griffin", username = Username("peter"), normalizedUsername = "peter"
      ))

      userExperimentRepo.save(UserExperiment(userId = userSuperAdminOpt.id.get, experimentType = ExperimentType.ADMIN))
      userExperimentRepo.save(UserExperiment(userId = userAdminOpt.id.get, experimentType = ExperimentType.ADMIN))
      userExperimentRepo.save(UserExperiment(userId = userNonAdminOpt.id.get, experimentType = ExperimentType.BYPASS_ABUSE_CHECKS))
      (userNonAdminOpt, userAdminOpt, userSuperAdminOpt)
    }
  }

  "AdminUserController" should {
    "deny non-superAdmins from adding/removing admins, allow superAdmins to do so" in {

      withDb(modules: _*) { implicit injector =>
        val (userNonAdminOpt, userAdminOpt, userSuperAdminOpt) = setup()
        val adminUserController = inject[AdminUserController]
        val userExperimentRepo = inject[UserExperimentRepo]
        val userRepo = inject[UserRepo]

        db.readWrite { implicit session =>
          // nonadmins cannot add/remove admins
          adminUserController.addExperiment(requesterUserId = userNonAdminOpt.id.get, userId = userNonAdminOpt.id.get, "admin") === Left("Failure")
          adminUserController.removeExperiment(requesterUserId = userNonAdminOpt.id.get, userId = userAdminOpt.id.get, "admin") === Left("Failure")

          // admins cannot add/remove admins
          adminUserController.addExperiment(requesterUserId = userAdminOpt.id.get, userId = userNonAdminOpt.id.get, "admin") === Left("Failure")
          adminUserController.removeExperiment(requesterUserId = userAdminOpt.id.get, userId = userNonAdminOpt.id.get, "admin") === Left("Failure")

          // superAdmins can add or remove admins
          adminUserController.addExperiment(requesterUserId = userSuperAdminOpt.id.get, userId = userNonAdminOpt.id.get, "admin") === Right(ExperimentType.ADMIN)
          userExperimentRepo.getUserExperiments(userNonAdminOpt.id.get).contains(ExperimentType.ADMIN) must equalTo(true)

          adminUserController.removeExperiment(requesterUserId = userSuperAdminOpt.id.get, userId = userAdminOpt.id.get, "admin") === Right(ExperimentType.ADMIN)
          userExperimentRepo.getUserExperiments(userAdminOpt.id.get).contains(ExperimentType.ADMIN) must equalTo(false)
        }

      }
    }
  }
}
