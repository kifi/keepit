package com.keepit.payments

import com.google.inject.{ Singleton, Inject, ImplementedBy }
import com.keepit.commanders.OrganizationCommander
import com.keepit.common.crypto.{ PublicIdConfiguration, PublicId }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.Database
import com.keepit.common.mail.{ SystemEmailAddress, EmailAddress }
import com.keepit.common.path.Path
import com.keepit.common.social.BasicUserRepo
import com.keepit.model._
import com.keepit.social.BasicUser
import org.joda.time.DateTime
import play.api.libs.json.{ Writes, Json }

@ImplementedBy(classOf[ActivityLogCommanderImpl])
trait ActivityLogCommander {
  def getAccountEvents(orgId: Id[Organization], fromIdOpt: Option[Id[AccountEvent]], limit: Limit): Seq[AccountEvent]
  def buildSimpleEventInfo(event: AccountEvent): SimpleAccountEventInfo
}

@Singleton
class ActivityLogCommanderImpl @Inject() (
    db: Database,
    paidPlanRepo: PaidPlanRepo,
    paidAccountRepo: PaidAccountRepo,
    accountEventRepo: AccountEventRepo,
    creditRewardRepo: CreditRewardRepo,
    basicUserRepo: BasicUserRepo,
    organizationRepo: OrganizationRepo,
    organizationCommander: OrganizationCommander,
    implicit val publicIdConfig: PublicIdConfiguration) extends ActivityLogCommander {

  private def orgId2AccountId(orgId: Id[Organization])(implicit session: RSession): Id[PaidAccount] = {
    paidAccountRepo.getAccountId(orgId)
  }

  def getAccountEvents(orgId: Id[Organization], fromIdOpt: Option[Id[AccountEvent]], limit: Limit): Seq[AccountEvent] = db.readOnlyMaster { implicit session =>
    val accountId = orgId2AccountId(orgId)
    accountEventRepo.getByAccountAndKinds(accountId, AccountEventKind.activityLog, fromIdOpt, limit)
  }

  def buildSimpleEventInfo(event: AccountEvent): SimpleAccountEventInfo = db.readOnlyMaster { implicit session =>
    import AccountEventAction._
    val maybeUser = event.whoDunnit.map(basicUserRepo.load)
    val org = organizationCommander.getBasicOrganizationHelper(paidAccountRepo.get(event.accountId).orgId).getOrElse(throw new Exception(s"Tried to build event info for dead org: $event"))
    implicit val orgHandle = org.handle
    val description: DescriptionElements = {
      import com.keepit.payments.{ DescriptionElements => Elements }
      event.action match {
        case RewardCredit(id) =>
          val creditReward = creditRewardRepo.get(id)
          creditReward.reward.kind match {
            case RewardKind.Coupon => Elements("You earned ", creditReward.credit, creditReward.code.map(code => Elements(" when ", basicUserRepo.load(code.usedBy), " redeemed ", code.code.value)), ".")
            case RewardKind.OrganizationCreation => Elements("You earned ", creditReward.credit, " because you're awesome!")
            case RewardKind.OrganizationReferral => Elements("You earned", creditReward.credit, creditReward.code.map(code => Elements("an organization you referred just upgraded")), ".")
          }
        case IntegrityError(err) => Elements("Found and corrected an error in the account.") // this is intentionally vague to avoid sending dangerous information to clients
        case SpecialCredit() => Elements("Special credit was granted to your team by Kifi Support", maybeUser.map(Elements(" thanks to ", _)), ".")
        case Refund() => Elements("A ", event.creditChange, " refund was issued to your card.")
        case PlanRenewal(planId, _, _, _, _) => Elements("Your ", paidPlanRepo.get(planId), " plan was renewed.")
        case Charge() =>
          val invoiceText = s"Invoice ${event.chargeId.map("#" + _).getOrElse(s"not found, please contact ${SystemEmailAddress.BILLING}")}"
          Elements("Your card was charged ", event.creditChange, s" for your balance. [$invoiceText]")
        case LowBalanceIgnored(amount) => s"Your account has a low balance of $amount."
        case ChargeFailure(amount, code, message) => s"We failed to process your payment, please update your payment information."
        case MissingPaymentMethod() => s"We failed to process your payment, please register a payment method."
        case UserJoinedOrganization(who, role) => maybeUser match {
          case Some(user) if user != who => Elements(basicUserRepo.load(who), " was added to your team by ", user, Some(role).collect { case OrganizationRole.ADMIN => " and is now an admin" },".")

          case _ => Elements(basicUserRepo.load(who), " joined your team", Some(role).collect { case OrganizationRole.ADMIN => " and is now an admin" },".")
        }
        case UserLeftOrganization(who, oldRole) => maybeUser match {
          case Some(user) if user != who => Elements(basicUserRepo.load(who), " was removed from your team by ", user, Some(oldRole).collect { case OrganizationRole.ADMIN => " and is no longer an admin" },".")
          case _ => Elements(basicUserRepo.load(who), " left your team", Some(oldRole).collect { case OrganizationRole.ADMIN => " and is no longer an admin" },".")
        }
        case OrganizationRoleChanged(who, oldRole, newRole) => maybeUser match {
          case Some(user) if user != who => Elements(basicUserRepo.load(who), "'s role was changed from ", oldRole, " to ", newRole, " by ", user, ".")
          case _ => Elements(basicUserRepo.load(who), "'s role changed from ", oldRole, " to ", newRole, ".")
        }
        case PlanChanged(oldPlanId, newPlanId, _) => Elements("Your plan was changed from ", paidPlanRepo.get(oldPlanId), " to ", paidPlanRepo.get(newPlanId), maybeUser.map(Elements(" by ", _)), ".")
        case PaymentMethodAdded(_, lastFour) => Elements(s"A credit card ending in $lastFour was added ", maybeUser.map(Elements(" by ", _)), ".")
        case DefaultPaymentMethodChanged(_, _, lastFour) => Elements(s"Your payment method was changed to the card ending in $lastFour", maybeUser.map(Elements(" by ", _)), ".")
        case AccountContactsChanged(userAdded: Option[Id[User]], userRemoved: Option[Id[User]], emailAdded: Option[EmailAddress], emailRemoved: Option[EmailAddress]) => {
          val singleContactChangedIn: Option[(Elements, Elements)] = (userAdded, userRemoved, emailAdded, emailRemoved) match {
            case (Some(addedUserId), None, None, None) => Some((basicUserRepo.load(addedUserId), "added to"))
            case (None, Some(removedUserId), None, None) => Some(basicUserRepo.load(removedUserId), "removed from")
            case (None, None, Some(addedEmailAddress), None) => Some(addedEmailAddress, "added to")
            case (None, None, None, Some(removedEmailAddress)) => Some(removedEmailAddress, "removed from")
            case _ => None
          }
          singleContactChangedIn match {
            case Some((contact, changedIn)) => Elements(contact, "was ", changedIn, " your billing contacts", maybeUser.map(Elements(" by ", _)), ".")
            case None => Elements("Your billing contacts were updated", maybeUser.map(Elements(" by ", _)), ".")
          }
        }
        case OrganizationCreated(initialPlanId, _) => Elements("The ", org, " team was created", maybeUser.map(Elements(" by ", _)), " and enrolled in the ", paidPlanRepo.get(initialPlanId), " plan.")
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

sealed trait DescriptionElements {
  def flatten: Seq[BasicElement]
}
case class SequenceOfElements(elements: Seq[DescriptionElements]) extends DescriptionElements {
  def flatten = elements.map(_.flatten).flatten
}
case class BasicElement(text: String, url: Option[String]) extends DescriptionElements {
  def flatten = Seq(this)
}
object DescriptionElements {
  def apply(elements: DescriptionElements*): SequenceOfElements = SequenceOfElements(elements)

  implicit def fromText(text: String): BasicElement = BasicElement(text, None)
  implicit def fromTextAndUrl(textAndUrl: (String, String)): BasicElement = BasicElement(textAndUrl._1, Some(textAndUrl._2))
  implicit def fromSeq[T](seq: Seq[T])(implicit toElements: T => DescriptionElements): SequenceOfElements = SequenceOfElements(seq.map(toElements))
  implicit def fromOption[T](opt: Option[T])(implicit toElements: T => DescriptionElements): SequenceOfElements = opt.toSeq

  implicit def fromBasicUser(user: BasicUser): BasicElement = user.firstName -> user.path.absolute
  implicit def fromBasicOrg(org: BasicOrganization): BasicElement = org.name -> org.path.absolute
  implicit def fromEmailAddress(email: EmailAddress): BasicElement = email.address
  implicit def fromDollarAmount(v: DollarAmount): BasicElement = v.toDollarString
  implicit def fromPaidPlanAndUrl(plan: PaidPlan)(implicit orgHandle: OrganizationHandle): BasicElement = plan.fullName -> Path(s"${orgHandle.value}/settings/plan").absolute
  implicit def fromRole(role: OrganizationRole): BasicElement = role.value

  implicit val flatWrites = {
    implicit val basicWrites = Json.writes[BasicElement]
    Writes[DescriptionElements] { description => Json.toJson(description.flatten) }
  }
}

case class SimpleAccountEventInfo(
  id: PublicId[AccountEvent],
  eventTime: DateTime,
  description: DescriptionElements,
  creditChange: String,
  paymentCharge: Option[String],
  memo: Option[String])

object SimpleAccountEventInfo {
  implicit val writes = Json.writes[SimpleAccountEventInfo]
}
