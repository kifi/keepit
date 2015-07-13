package com.keepit.commanders

import com.google.inject.Injector
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.model.OrganizationFactoryHelper._
import com.keepit.model.UserFactoryHelper._
import com.keepit.model.{ OrganizationFactory, OrganizationMembershipCandidateRepo, UserFactory }
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification

import scala.concurrent.Await
import scala.concurrent.duration._

class OrganizationMembershipCandidateCommanderTest extends Specification with ShoeboxTestInjector {
  val modules = Seq(
    FakeExecutionContextModule(),
    FakeABookServiceClientModule(),
    FakeSocialGraphModule()
  )
  "OrganizationMembershipCandidateCommander" should {
    "add membership candidates to an org" in {
      withDb(modules: _*) { implicit injector =>
        val orgMembershipCandidateRepo = inject[OrganizationMembershipCandidateRepo]
        val (org, owner, users) = db.readWrite { implicit session =>
          val owner = UserFactory.user().saved
          val users = UserFactory.users(10).saved
          val org = OrganizationFactory.organization().withName("Kifi").withOwner(owner).saved
          (org, owner, users)
        }

        val userIds = users.map(_.id.get).toSet
        Await.result(commander.addCandidates(org.id.get, userIds), Duration(5, SECONDS))

        db.readOnlyMaster { implicit session =>
          orgMembershipCandidateRepo.getAllByOrgId(org.id.get).map(_.userId).toSet === userIds
        }
      }
    }
    "remove membership candidates from an org" in {
      withDb(modules: _*) { implicit injector =>
        val orgMembershipCandidateRepo = inject[OrganizationMembershipCandidateRepo]
        val (org, owner, users) = db.readWrite { implicit session =>
          val owner = UserFactory.user().saved
          val users = UserFactory.users(10).saved
          val org = OrganizationFactory.organization().withName("Kifi").withOwner(owner).saved
          (org, owner, users)
        }

        val userIds = users.map(_.id.get).toSet
        Await.result(commander.addCandidates(org.id.get, userIds), Duration(5, SECONDS))

        db.readOnlyMaster { implicit session =>
          orgMembershipCandidateRepo.getAllByOrgId(org.id.get).map(_.userId).toSet === userIds
        }

        val toBeRemoved = users.take(3).map(_.id.get).toSet
        Await.result(commander.removeCandidates(org.id.get, toBeRemoved), Duration(5, SECONDS))

        db.readOnlyMaster { implicit session =>
          orgMembershipCandidateRepo.getAllByOrgId(org.id.get).map(_.userId).toSet === userIds -- toBeRemoved
        }
      }
    }
    "reactivate membership candidates in an org" in {
      withDb(modules: _*) { implicit injector =>
        val orgMembershipCandidateRepo = inject[OrganizationMembershipCandidateRepo]
        val (org, owner, users) = db.readWrite { implicit session =>
          val owner = UserFactory.user().saved
          val users = UserFactory.users(10).saved
          val org = OrganizationFactory.organization().withName("Kifi").withOwner(owner).saved
          (org, owner, users)
        }

        val userIds = users.map(_.id.get).toSet

        Await.result(commander.addCandidates(org.id.get, userIds), Duration(5, SECONDS))

        db.readOnlyMaster { implicit session =>
          orgMembershipCandidateRepo.getAllByOrgId(org.id.get).map(_.userId).toSet === userIds
        }

        val toBeRemoved = users.take(3).map(_.id.get).toSet
        val removals = commander.removeCandidates(org.id.get, toBeRemoved)
        Await.result(removals, Duration(5, SECONDS))

        db.readOnlyMaster { implicit session =>
          orgMembershipCandidateRepo.getAllByOrgId(org.id.get).map(_.userId).toSet === userIds -- toBeRemoved
        }

        Await.result(commander.addCandidates(org.id.get, toBeRemoved), Duration(5, SECONDS))

        db.readOnlyMaster { implicit session =>
          orgMembershipCandidateRepo.getAllByOrgId(org.id.get).map(_.userId).toSet === userIds
        }
      }
    }
    "invite membership candidates to become real members of an org" in {
      withDb(modules: _*) { implicit injector =>
        val orgMembershipCandidateRepo = inject[OrganizationMembershipCandidateRepo]
        val (org, owner, users) = db.readWrite { implicit session =>
          val owner = UserFactory.user().withEmailAddress("ryan@kifi.com").saved
          val users = UserFactory.users(10).map(_.withEmailAddress("ryan@kifi.com")).saved
          val org = OrganizationFactory.organization().withName("Kifi").withOwner(owner).saved
          (org, owner, users)
        }

        val userIds = users.map(_.id.get).toSet
        Await.result(commander.addCandidates(org.id.get, userIds), Duration(5, SECONDS))

        db.readOnlyMaster { implicit session =>
          orgMembershipCandidateRepo.getAllByOrgId(org.id.get).map(_.userId).toSet === userIds
        }

        val result = Await.result(commander.inviteCandidates(org.id.get), Duration(5, SECONDS))
        println(result)

        result.isRight === true
      }
    }
  }
  private def commander(implicit injector: Injector) = inject[OrganizationMembershipCandidateCommander]
}
