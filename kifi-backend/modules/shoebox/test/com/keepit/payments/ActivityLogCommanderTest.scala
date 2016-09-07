package com.keepit.payments

import com.google.inject.Injector
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.time._
import com.keepit.common.util.{ DollarAmount, DescriptionElements }
import com.keepit.model.OrganizationFactoryHelper.OrganizationPersister
import com.keepit.model.UserFactoryHelper.UserPersister
import com.keepit.model.{ OrganizationRole, User, OrganizationFactory, UserFactory }
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.SpecificationLike
import play.api.libs.json.{ JsNull, JsObject }

class ActivityLogCommanderTest extends SpecificationLike with ShoeboxTestInjector {
  val modules = Seq(
    FakeExecutionContextModule(),
    FakeStripeClientModule()
  )

  args(skipAll = true)

  "ActivityLogCommanderTest" should {
    def display(event: AccountEvent)(implicit injector: Injector): String = {
      DescriptionElements.formatPlain(activityLogCommander.buildSimpleEventInfo(event).description)
    }
    "display events in a sane way" in {
      def setup()(implicit injector: Injector): Seq[AccountEvent] = db.readWrite { implicit session =>
        val owner = UserFactory.user().withName("Owner", "OwnerLN").saved
        val member = UserFactory.user().withName("Member", "MemberLN").saved
        val org = OrganizationFactory.organization().withName("Org").withOwner(owner).withMembers(Seq(member)).saved
        val account = paidAccountRepo.getByOrgId(org.id.get)
        val plan = paidPlanRepo.get(account.planId)

        def event(who: Option[User], action: AccountEventAction) = inject[AccountEventRepo].save(AccountEvent(
          eventTime = currentDateTime,
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
        ))
        val createReward = creditRewardCommander.createCreditReward(CreditReward(
          accountId = account.id.get, credit = DollarAmount.dollars(42), applied = None, unrepeatable = None, code = None,
          reward = Reward(RewardKind.OrganizationCreation)(RewardKind.OrganizationCreation.Created)(None)
        ), Some(owner.id.get)).get
        Seq(
          event(Some(owner), AccountEventAction.OrganizationCreated(account.planId, None)),
          event(Some(owner), AccountEventAction.Charge()),
          event(Some(owner), AccountEventAction.Refund(Id(1), StripeTransactionId("ch_42"))),
          event(Some(owner), AccountEventAction.ChargeFailure(DollarAmount.dollars(1), ":(", "Failed")),
          event(Some(owner), AccountEventAction.RefundFailure(Id(1), StripeTransactionId("ch_42"), ":(", "Failed")),
          event(Some(owner), AccountEventAction.DefaultPaymentMethodChanged(None, Id(1), "4242")),
          event(Some(owner), AccountEventAction.PlanRenewal.from(plan, account)),
          event(Some(owner), AccountEventAction.PlanChanged(plan.id.get, plan.id.get, None)),
          event(Some(owner), AccountEventAction.RewardCredit(createReward.id.get)),
          event(Some(owner), AccountEventAction.SpecialCredit()),
          event(Some(owner), AccountEventAction.UserJoinedOrganization(member.id.get, OrganizationRole.MEMBER)),
          event(Some(owner), AccountEventAction.UserJoinedOrganization(member.id.get, OrganizationRole.ADMIN)),
          event(Some(owner), AccountEventAction.UserLeftOrganization(member.id.get, OrganizationRole.ADMIN)),
          event(None, AccountEventAction.UserLeftOrganization(owner.id.get, OrganizationRole.ADMIN)),
          event(Some(owner), AccountEventAction.OrganizationRoleChanged(member.id.get, OrganizationRole.MEMBER, OrganizationRole.ADMIN))
        )
      }

      withDb(modules: _*) { implicit injector =>
        val events = setup()
        events.map(_.action.eventType).toSet === AccountEventKind.activityLog

        // events.foreach(display _ andThen println)
        val e = events.iterator
        display(e.next()) === "The Org team was created by Owner and enrolled in the Free plan."
        display(e.next()) === "Your card was charged $0.00 for your balance."
        display(e.next()) === "A $0.00 refund was issued to your card."
        display(e.next()) === "We failed to process your payment, please update your payment information."
        display(e.next()) === "We failed to refund your card."
        display(e.next()) === "Your payment method was changed to the card ending in 4242 by Owner."
        display(e.next()) === "Your Free plan was renewed."
        display(e.next()) === "Your plan was changed from Free to Free by Owner."
        display(e.next()) === "You earned $42.00 because you created a team on Kifi. Thanks for being awesome! :)"
        display(e.next()) === "Special credit was granted to your team by Kifi Support thanks to Owner."
        display(e.next()) === "Member was added to your team by Owner."
        display(e.next()) === "Member was added to your team by Owner and is now an admin."
        display(e.next()) === "Member was removed from your team by Owner and is no longer an admin."
        display(e.next()) === "Owner left your team and is no longer an admin."
        display(e.next()) === "Member's role was changed from member to admin by Owner."
        e.hasNext === false

        1 === 1
      }
    }
  }
}
