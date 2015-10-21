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
  def getAccountEvents(orgId: Id[Organization], bookendOpt: Option[Id[AccountEvent]], limit: Limit): Seq[AccountEvent]
  def buildSimpleEventInfo(event: AccountEvent): SimpleAccountEventInfo
}

@Singleton
class ActivityLogCommanderImpl @Inject() (
    db: Database,
    paidPlanRepo: PaidPlanRepo,
    paidAccountRepo: PaidAccountRepo,
    accountEventRepo: AccountEventRepo,
    basicUserRepo: BasicUserRepo,
    organizationRepo: OrganizationRepo,
    organizationCommander: OrganizationCommander,
    implicit val publicIdConfig: PublicIdConfiguration) extends ActivityLogCommander {

  private def orgId2AccountId(orgId: Id[Organization])(implicit session: RSession): Id[PaidAccount] = {
    paidAccountRepo.getAccountId(orgId)
  }

  def getAccountEvents(orgId: Id[Organization], bookendOpt: Option[Id[AccountEvent]], limit: Limit): Seq[AccountEvent] = db.readOnlyMaster { implicit session =>
    val accountId = orgId2AccountId(orgId)
    accountEventRepo.getByAccountAndKinds(accountId, AccountEventKind.activityLog, bookendOpt, limit)
  }

  def buildSimpleEventInfo(event: AccountEvent): SimpleAccountEventInfo = db.readOnlyMaster { implicit session =>
    import AccountEventAction._
    val maybeUser = event.whoDunnit.map(basicUserRepo.load)
    val org = organizationCommander.getBasicOrganizationHelper(paidAccountRepo.get(event.accountId).orgId).getOrElse(throw new Exception(s"Tried to build event info for dead org: $event"))
    implicit val orgHandle = org.handle
    val description: DescriptionElements = {
      import com.keepit.payments.{ DescriptionElements => Elements }
      event.action match {
        case IntegrityError(err) => Elements("Found and corrected an error in the account") // this is intentionally vague to avoid sending dangerous information to clients
        case SpecialCredit() => Elements("Special credit was granted to your team by Kifi Support", maybeUser.map(Elements("thanks to", _)))
        case ChargeBack() => s"A ${event.creditChange.toDollarString} refund was issued to your card"
        case PlanBilling(planId, _, _, _, _) => s"Your ${paidPlanRepo.get(planId)} plan was renewed."
        case Charge() =>
          val invoiceText = s"Invoice ${event.chargeId.map("#" + _).getOrElse(s"not found, please contact ${SystemEmailAddress.BILLING}")}"
          s"Your card was charged ${event.creditChange.toDollarString} for your current balance. [$invoiceText]"
        case LowBalanceIgnored(amount) => s"Your account has a low balance of $amount."
        case ChargeFailure(amount, code, message) => s"We failed to process your balance, please update your payment information."
        case MissingPaymentMethod() => s"We failed to process your balance, please register a default payment method."
        case UserAdded(who) => Elements(basicUserRepo.load(who), "was added to your team", maybeUser.map(Elements("by", _)))
        case UserRemoved(who) => maybeUser match {
          case Some(`who`) => Elements(basicUserRepo.load(who), "left your team")
          case _ => Elements(basicUserRepo.load(who), "was removed from your team", maybeUser.map(Elements("by", _)))
        }
        case AdminAdded(who) => Elements(basicUserRepo.load(who), "was made an admin", maybeUser.map(Elements("by", _)))
        case AdminRemoved(who) => Elements(basicUserRepo.load(who), "(admin) was made a member by", maybeUser.map(Elements("by", _)))
        case PlanChanged(oldPlanId, newPlanId) => Elements("Your plan was changed from", paidPlanRepo.get(oldPlanId), "to", paidPlanRepo.get(newPlanId), maybeUser.map(Elements("by", _)))
        case PaymentMethodAdded(_, lastFour) => Elements(s"A credit card ending in $lastFour was added", maybeUser.map(Elements("by", _)))
        case DefaultPaymentMethodChanged(_, _, lastFour) => Elements(s"Your team's default payment method was changed to the card ending in $lastFour", maybeUser.map(Elements("by", _)))
        case AccountContactsChanged(userAdded: Option[Id[User]], userRemoved: Option[Id[User]], emailAdded: Option[EmailAddress], emailRemoved: Option[EmailAddress]) => {
          val singleContactChangedIn: Option[(Elements, Elements)] = (userAdded, userRemoved, emailAdded, emailRemoved) match {
            case (Some(addedUserId), None, None, None) => Some((basicUserRepo.load(addedUserId), "added to"))
            case (None, Some(removedUserId), None, None) => Some(basicUserRepo.load(removedUserId), "removed from")
            case (None, None, Some(addedEmailAddress), None) => Some(addedEmailAddress, "added to")
            case (None, None, None, Some(removedEmailAddress)) => Some(removedEmailAddress, "removed from")
            case _ => None
          }
          singleContactChangedIn match {
            case Some((contact, changedIn)) => Elements(contact, "was", changedIn, "your billing contacts", maybeUser.map(Elements("by", _)))
            case None => Elements("Your billing contacts were updated", maybeUser.map(Elements("by", _)))
          }
        }
        case OrganizationCreated(initialPlanId) => Elements("The", org, "team was created by", maybeUser.get, "and enrolled in", paidPlanRepo.get(initialPlanId))
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

sealed trait DescriptionElements
object DescriptionElements {
  case class SequenceOfElements(elements: Seq[DescriptionElements]) extends DescriptionElements
  case class BasicElement(text: String, url: Option[String]) extends DescriptionElements

  def apply(elements: DescriptionElements*): SequenceOfElements = SequenceOfElements(elements)

  implicit def fromText(text: String): BasicElement = BasicElement(text, None)
  implicit def fromTextAndUrl(textAndUrl: (String, String)): BasicElement = BasicElement(textAndUrl._1, Some(textAndUrl._2))
  implicit def fromSeq[T](seq: Seq[T])(implicit toElements: T => DescriptionElements): SequenceOfElements = SequenceOfElements(seq.map(toElements))
  implicit def fromOption[T](opt: Option[T])(implicit toElements: T => DescriptionElements): SequenceOfElements = opt.toSeq

  implicit def fromBasicUser(user: BasicUser): BasicElement = user.fullName -> user.path.relative
  implicit def fromBasicOrg(org: BasicOrganization): BasicElement = org.name -> org.path.relative
  implicit def fromEmailAddress(email: EmailAddress): BasicElement = email.address
  implicit def fromPaidPlanAndUrl(plan: PaidPlan)(implicit orgHandle: OrganizationHandle): BasicElement = plan.fullName -> s"/${orgHandle.value}/settings/plan"

  private def flatten(description: DescriptionElements): Seq[BasicElement] = description match {
    case SequenceOfElements(elements) => elements.map(flatten).flatten
    case element: DescriptionElements.BasicElement => Seq(element)
  }
  implicit val flatWrites = {
    implicit val basicWrites = Json.writes[BasicElement]
    Writes[DescriptionElements] { description => Json.toJson(flatten(description)) }
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
