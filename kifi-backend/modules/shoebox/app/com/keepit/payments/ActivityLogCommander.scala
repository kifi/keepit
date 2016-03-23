package com.keepit.payments

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.commanders.gen.BasicOrganizationGen
import com.keepit.commanders.{ PathCommander, OrganizationInfoCommander }
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.Database
import com.keepit.common.mail.EmailAddress
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.util.{ DescriptionElements => Elements, LinkElement }
import com.keepit.common.util.DescriptionElements._
import com.keepit.model._
import com.keepit.social.BasicUser
import org.joda.time.DateTime
import play.api.libs.json.Json

@ImplementedBy(classOf[ActivityLogCommanderImpl])
trait ActivityLogCommander {
  def getAccountEvents(orgId: Id[Organization], fromIdOpt: Option[Id[AccountEvent]], limit: Limit, inclusive: Boolean = false): Seq[AccountEvent]
  def buildSimpleEventInfo(event: AccountEvent): SimpleAccountEventInfo
  def buildSimpleEventInfoHelper(event: AccountEvent)(implicit session: RSession): SimpleAccountEventInfo
}

@Singleton
class ActivityLogCommanderImpl @Inject() (
    db: Database,
    paidPlanRepo: PaidPlanRepo,
    paidAccountRepo: PaidAccountRepo,
    accountEventRepo: AccountEventRepo,
    creditRewardRepo: CreditRewardRepo,
    creditRewardInfoCommander: CreditRewardInfoCommander,
    pathCommander: PathCommander,
    basicUserRepo: BasicUserRepo,
    basicOrganizationGen: BasicOrganizationGen,
    implicit val publicIdConfig: PublicIdConfiguration) extends ActivityLogCommander {

  private def orgId2AccountId(orgId: Id[Organization])(implicit session: RSession): Id[PaidAccount] = {
    paidAccountRepo.getAccountId(orgId)
  }

  def getAccountEvents(orgId: Id[Organization], fromIdOpt: Option[Id[AccountEvent]], limit: Limit, inclusive: Boolean = false): Seq[AccountEvent] = db.readOnlyMaster { implicit session =>
    val accountId = orgId2AccountId(orgId)
    accountEventRepo.getByAccountAndKinds(accountId, AccountEventKind.activityLog, fromIdOpt, limit, inclusive)
  }

  private def getUser(id: Id[User])(implicit session: RSession): BasicUser = basicUserRepo.load(id)
  private def getOrg(id: Id[Organization])(implicit session: RSession): BasicOrganization = basicOrganizationGen.getBasicOrganizationHelper(id).getOrElse(throw new Exception(s"Tried to build event info for dead org: $id"))

  def buildSimpleEventInfo(event: AccountEvent): SimpleAccountEventInfo = db.readOnlyMaster { implicit s =>
    buildSimpleEventInfoHelper(event)
  }

  def buildSimpleEventInfoHelper(event: AccountEvent)(implicit session: RSession): SimpleAccountEventInfo = {
    import AccountEventAction._
    val account = paidAccountRepo.get(event.accountId)
    val org = getOrg(account.orgId)
    lazy val maybeUser = event.whoDunnit.map(getUser)
    lazy val planPage = LinkElement(pathCommander.orgPlanPage(org))
    implicit val orgHandle = org.handle
    val description: Elements = {
      event.action match {
        case RewardCredit(id) => creditRewardInfoCommander.getDescription(creditRewardRepo.get(id))
        case IntegrityError(err) => Elements("Found and corrected an error in the account.") // this is intentionally vague to avoid sending dangerous information to clients
        case SpecialCredit() => Elements("Special credit was granted to your team by Kifi Support", maybeUser.map(Elements("thanks to", _)), ".")
        case Refund(_, _) => Elements("A", -event.creditChange, "refund was issued to your card", event.chargeId.map(id => s"(ref. ${id.id})"), ".")
        case RefundFailure(_, _, _, _) => s"We failed to refund your card."
        case PlanRenewal(planId, _, _, _, _) => Elements("Your", paidPlanRepo.get(planId).fullName --> planPage, "plan was renewed.")
        case Charge() => Elements("Your card was charged", event.creditChange, s"for your balance", event.chargeId.map(id => s"(ref. ${id.id})"), ".")
        case LowBalanceIgnored(amount) => s"Your account has a low balance of $amount."
        case ChargeFailure(amount, code, message) => s"We failed to process your payment, please update your payment information."
        case MissingPaymentMethod() => s"We failed to process your payment, please register a payment method."
        case UserJoinedOrganization(who, role) => event.whoDunnit match {
          case Some(user) if user != who => Elements(getUser(who), "was added to your team by", maybeUser, Some(role).collect { case OrganizationRole.ADMIN => "and is now an admin" }, ".")
          case _ => Elements(getUser(who), "joined your team", Some(role).collect { case OrganizationRole.ADMIN => "and is now an admin" }, ".")
        }
        case UserLeftOrganization(who, oldRole) => event.whoDunnit match {
          case Some(user) if user != who => Elements(getUser(who), "was removed from your team by", maybeUser, Some(oldRole).collect { case OrganizationRole.ADMIN => "and is no longer an admin" }, ".")
          case _ => Elements(getUser(who), "left your team", Some(oldRole).collect { case OrganizationRole.ADMIN => "and is no longer an admin" }, ".")
        }
        case OrganizationRoleChanged(who, oldRole, newRole) => event.whoDunnit match {
          case Some(user) if user != who => Elements(getUser(who), "'s role was changed from", oldRole, "to", newRole, "by", maybeUser, ".")
          case _ => Elements(getUser(who), "'s role changed from", oldRole, "to", newRole, ".")
        }
        case PlanChanged(oldPlanId, newPlanId, _) =>
          Elements(
            "Your plan was changed from", paidPlanRepo.get(oldPlanId).fullName --> planPage,
            "to", paidPlanRepo.get(newPlanId).fullName --> planPage, maybeUser.map(Elements("by", _)), "."
          )
        case PaymentMethodAdded(_, lastFour) => Elements(s"A credit card ending in $lastFour was added", maybeUser.map(Elements("by", _)), ".")
        case DefaultPaymentMethodChanged(_, _, lastFour) => Elements(s"Your payment method was changed to the card ending in $lastFour", maybeUser.map(Elements("by", _)), ".")
        case AccountContactsChanged(userAdded: Option[Id[User]], userRemoved: Option[Id[User]], emailAdded: Option[EmailAddress], emailRemoved: Option[EmailAddress]) =>
          val singleContactChangedIn: Option[(Elements, Elements)] = (userAdded, userRemoved, emailAdded, emailRemoved) match {
            case (Some(addedUserId), None, None, None) => Some((getUser(addedUserId), "added to"))
            case (None, Some(removedUserId), None, None) => Some(getUser(removedUserId), "removed from")
            case (None, None, Some(addedEmailAddress), None) => Some(addedEmailAddress, "added to")
            case (None, None, None, Some(removedEmailAddress)) => Some(removedEmailAddress, "removed from")
            case _ => None
          }
          singleContactChangedIn match {
            case Some((contact, changedIn)) => Elements(contact, "was", changedIn, "your billing contacts", maybeUser.map(Elements("by", _)), ".")
            case None => Elements("Your billing contacts were updated", maybeUser.map(Elements("by", _)), ".")
          }
        case OrganizationCreated(initialPlanId, _) => Elements(
          "The", org, "team was created", maybeUser.map(Elements("by", _)),
          "and enrolled in the", paidPlanRepo.get(initialPlanId).fullName --> planPage, "plan."
        )
      }
    }
    SimpleAccountEventInfo(
      id = AccountEvent.publicId(event.id.get),
      eventTime = event.eventTime,
      description = description,
      creditChange = event.creditChange.toDollarString,
      paymentCharge = event.paymentCharge.map(_.toDollarString),
      memo = event.memo
    )
  }
}

case class SimpleAccountEventInfo(
  id: PublicId[AccountEvent],
  eventTime: DateTime,
  description: Elements,
  creditChange: String,
  paymentCharge: Option[String],
  memo: Option[String])

object SimpleAccountEventInfo {
  implicit val writes = Json.writes[SimpleAccountEventInfo]
}
