package com.keepit.commanders

import com.google.inject.Injector
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.actor.TestKitSupport
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.model.OrganizationFactoryHelper._
import com.keepit.model.UserFactoryHelper._
import com.keepit.model._
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.SpecificationLike

class OrganizationMembershipPokeCommanderTest extends TestKitSupport with SpecificationLike with ShoeboxTestInjector {
  "OrganizationMembershipPokeCommander" should {
    val modules = Seq(
      FakeExecutionContextModule(),
      FakeABookServiceClientModule(),
      FakeSocialGraphModule()
    )
    def commander(implicit injector: Injector) = inject[OrganizationMembershipPokeCommander]
    def setup()(implicit injector: Injector) = {
      db.readWrite { implicit session =>
        val owner = UserFactory.user().saved
        val invitee = UserFactory.user().saved
        val rando = UserFactory.user().saved
        val org = OrganizationFactory.organization().withName("Moneybags Inc").withOwner(owner).withInvitedUsers(Seq(invitee)).saved
        (org, owner, invitee, rando)
      }
    }
    "poke organizations to request membership" in {
      "let a user poke an org" in {
        withDb(modules: _*) { implicit injector =>
          val (org, _, _, rando) = setup()
          val result = commander.poke(rando.id.get, org.id.get)
          result.isRight === true
          db.readOnlyMaster { implicit session =>
            inject[OrganizationMembershipPokeRepo].count === 1
            inject[OrganizationMembershipPokeRepo].getByOrgIdAndUserId(orgId = org.id.get, userId = rando.id.get).isDefined === true
          }
        }
      }
      "fail if the organization is secret" in {
        withDb(modules: _*) { implicit injector =>
          val (org, _, _, rando) = setup()
          db.readWrite { implicit session => inject[OrganizationRepo].save(org.withBasePermissions(BasePermissions(Map(None -> Set())))) }

          val result = commander.poke(rando.id.get, org.id.get)
          result.isLeft === true
          db.readOnlyMaster { implicit session => inject[OrganizationMembershipPokeRepo].count === 0 }
        }
      }
      "fail if the user is already a member" in {
        withDb(modules: _*) { implicit injector =>
          val (org, member, _, _) = setup()
          val result = commander.poke(member.id.get, org.id.get)
          result.isLeft === true
          result.left.get === OrganizationFail.ALREADY_A_MEMBER
          db.readOnlyMaster { implicit session => inject[OrganizationMembershipPokeRepo].count === 0 }
        }
      }
      "fail if the user is already invited" in {
        withDb(modules: _*) { implicit injector =>
          val (org, _, invitee, _) = setup()
          val result = commander.poke(invitee.id.get, org.id.get)
          result.isLeft === true
          result.left.get === OrganizationFail.ALREADY_INVITED
          db.readOnlyMaster { implicit session => inject[OrganizationMembershipPokeRepo].count === 0 }
        }
      }
      "fail if the user had poked too many times recently" in {
        withDb(modules: _*) { implicit injector =>
          val (org, _, _, rando) = setup()
          val result1 = commander.poke(rando.id.get, org.id.get)
          result1.isRight === true
          db.readOnlyMaster { implicit session =>
            inject[OrganizationMembershipPokeRepo].count === 1
            inject[OrganizationMembershipPokeRepo].getByOrgIdAndUserId(orgId = org.id.get, userId = rando.id.get).isDefined === true
          }
          val result2 = commander.poke(rando.id.get, org.id.get)
          result2.isLeft === true
          db.readOnlyMaster { implicit session =>
            inject[OrganizationMembershipPokeRepo].count === 1
            inject[OrganizationMembershipPokeRepo].getByOrgIdAndUserId(orgId = org.id.get, userId = rando.id.get).isDefined === true
          }
        }
      }
    }
  }
}
