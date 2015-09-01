package com.keepit.payments

import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.test.ShoeboxTestInjector
import com.keepit.common.actor.TestKitSupport
import com.keepit.commanders.OrganizationCommander
import com.keepit.model.{ Name, UserFactory, OrganizationCreateRequest, OrganizationInitialValues }
import com.keepit.model.UserFactoryHelper._
import com.keepit.heimdal.HeimdalContext

import org.specs2.mutable.SpecificationLike

import scala.concurrent.{ Future, Await }
import scala.concurrent.duration.Duration

class AccountLockHelperTest extends SpecificationLike with ShoeboxTestInjector {
  implicit val context = HeimdalContext.empty
  val modules = Seq(
    FakeExecutionContextModule()
  )

  "AccountLockHelper" should {
    "allow only one lock at a time, both sync and async" in {
      withDb(modules: _*) { implicit injector =>
        val helper = inject[AccountLockHelper]
        val orgCommander = inject[OrganizationCommander]

        inject[PlanManagementCommander].createNewPlan(Name[PaidPlan]("Test"), BillingCycle(1), DollarAmount(0))
        val user = db.readWrite { implicit session => UserFactory.user().withName("Mr", "Spock").saved }
        val createRequest = OrganizationCreateRequest(requesterId = user.id.get, OrganizationInitialValues(name = "Kifi"))
        val createResponse = orgCommander.createOrganization(createRequest)
        val org = createResponse.right.get.newOrg

        Some(true) === helper.maybeSessionWithAccountLock(org.id.get) { s1 =>
          None === helper.maybeSessionWithAccountLock(org.id.get) { s2 =>
            true
          }
          Await.result(Future {
            None === helper.maybeSessionWithAccountLock(org.id.get) { s3 =>
              true
            }
          }, Duration.Inf)
          true
        }

        Some(true) === helper.maybeSessionWithAccountLock(org.id.get) { s4 => true }

      }
    }
  }

}
