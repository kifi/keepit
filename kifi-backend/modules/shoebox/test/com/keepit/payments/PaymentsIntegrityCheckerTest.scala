package com.keepit.payments

import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.actor.TestKitSupport
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.crypto.FakeCryptoModule
import com.keepit.common.db.Id
import com.keepit.common.mail.FakeMailModule
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.common.store.FakeShoeboxStoreModule
import com.keepit.common.util.DollarAmount
import com.keepit.eliza.FakeElizaServiceClientModule
import com.keepit.heimdal.FakeHeimdalServiceClientModule
import com.keepit.model.OrganizationFactoryHelper._
import com.keepit.model.UserFactoryHelper._
import com.keepit.model._
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.SpecificationLike
import play.api.libs.json.{ JsSuccess, Json }

class PaymentsIntegrityCheckerTest extends TestKitSupport with SpecificationLike with ShoeboxTestInjector {
  def modules = Seq(
    FakeExecutionContextModule(),
    FakeSearchServiceClientModule(),
    FakeMailModule(),
    FakeShoeboxStoreModule(),
    FakeCryptoModule(),
    FakeSocialGraphModule(),
    FakeABookServiceClientModule(),
    FakeElizaServiceClientModule(),
    FakeHeimdalServiceClientModule()
  )

  "PaymentsIntegrityChecker" should {
    "serialize and deserialize errors" in {
      val all = Seq[PaymentsIntegrityError](
        PaymentsIntegrityError.CouldNotGetAccountLock("cngal"),
        PaymentsIntegrityError.InconsistentAccountBalance(DollarAmount.cents(42), DollarAmount.dollars(19)),
        PaymentsIntegrityError.MissingOrganizationMember(Id[User](42)),
        PaymentsIntegrityError.ExtraOrganizationMember(Id[User](1231))
      )
      val dump = Json.toJson(all)
      dump.validate[Seq[PaymentsIntegrityError]] === JsSuccess(all)
    }
    "do nothing if there's nothing wrong" in {
      withDb(modules: _*) { implicit injector =>
        val (org, owner, members) = db.readWrite { implicit session =>
          val owner = UserFactory.user().saved
          val members = UserFactory.users(10).saved
          val org = OrganizationFactory.organization().withOwner(owner).withMembers(members).saved
          (org, owner, members)
        }
        paymentsChecker.checkAccounts(modulus = 1)
        db.readOnlyMaster { implicit session =>
          inject[PaidAccountRepo].aTonOfRecords.filter(_.frozen).map(_.orgId) === Seq.empty
        }
      }
    }
    "freak out if the account charges don't add up to the credit" in {
      withDb(modules: _*) { implicit injector =>
        val (org, owner, members) = db.readWrite { implicit session =>
          val owner = UserFactory.user().saved
          val members = UserFactory.users(10).saved
          val org = OrganizationFactory.organization().withOwner(owner).withMembers(members).saved
          (org, owner, members)
        }

        // first, break one of the events
        db.readWrite { implicit session =>
          val accountId = inject[PaidAccountRepo].getByOrgId(org.id.get).id.get
          val lastEvent = inject[AccountEventRepo].getAllByAccount(accountId).last
          inject[AccountEventRepo].save(lastEvent.copy(creditChange = lastEvent.creditChange + DollarAmount.dollars(1)))
        }

        paymentsChecker.checkAccounts(modulus = 1)
        db.readOnlyMaster { implicit session =>
          paidAccountRepo.aTonOfRecords.filter(_.frozen).map(_.orgId) === Seq(org.id.get)
        }
      }
    }
  }
}
