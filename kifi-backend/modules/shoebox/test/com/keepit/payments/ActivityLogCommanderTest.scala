package com.keepit.payments

import com.google.inject.Injector
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.time.FakeClock
import com.keepit.model.OrganizationFactoryHelper.OrganizationPersister
import com.keepit.model.UserFactoryHelper.UserPersister
import com.keepit.model.{User, OrganizationFactory, UserFactory}
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.SpecificationLike
import play.api.libs.json.{JsNull, JsObject}

class ActivityLogCommanderTest extends SpecificationLike with ShoeboxTestInjector {
  val modules = Seq(
    FakeExecutionContextModule(),
    FakeStripeClientModule()
  )

  "ActivityLogCommanderTest" should {
    def display(input: DescriptionElements): String = {
      DescriptionElements.flatWrites.writes(input).as[Seq[JsObject]].map(obj => (obj \ "text").as[String]).mkString
    }
    "display events in a sane way" in {
      def setup()(implicit injector: Injector): Seq[AccountEvent] = db.readWrite { implicit session =>
          val owner = UserFactory.user().withName("Owner", "OwnerLN").saved
          val org = OrganizationFactory.organization().withName("Org").withOwner(owner).saved
          val account = paidAccountRepo.getByOrgId(org.id.get)

          def event(who: Option[User], action: AccountEventAction) = AccountEvent(
            eventTime = inject[FakeClock].now,
            whoDunnit = who.map(_.id.get),
            whoDunnitExtra = JsNull,
            accountId = account.id.get,
            kifiAdminInvolved = None,
            action = action,
            creditChange = DollarAmount.dollars(0),
            paymentMethod = None,
            paymentCharge = None,
            memo = None,
            chargeId = None
          )
        Seq(
          event(None, AccountEventAction.OrganizationCreated(account.planId, None))
        )
      }

      withDb(modules: _*) { implicit injector =>
        val events = setup()
        events.map(_.action.eventType).toSet === AccountEventKind.activityLog

        val eventsByKind = events.map(e => e.action.eventType -> e).toMap
        display(eventsByKind(AccountEventKind.AccountContactsChanged)) === "Alice was added as a contact"
        1 === 1
      }
    }
  }
}
