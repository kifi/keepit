package com.keepit.commanders.gen

import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.store.S3ImageConfig
import com.keepit.common.util.DescriptionElements._
import com.keepit.common.util._
import com.keepit.discussion.{ CrossServiceDiscussion, Message }
import com.keepit.model.BasicKeepEvent.BasicKeepEventId
import com.keepit.model.KeepEventData.{ EditTitle, ModifyRecipients }
import com.keepit.model._
import com.keepit.social.{ BasicAuthor, BasicUser }
import play.api.libs.functional.syntax._

object KeepActivityGen {
  def generateKeepActivity(
    keep: Keep,
    sourceAttrOpt: Option[(SourceAttribution, Option[BasicUser])],
    events: Seq[KeepEvent],
    discussionOpt: Option[CrossServiceDiscussion],
    ktls: Seq[KeepToLibrary],
    ktus: Seq[KeepToUser],
    maxEvents: Int)(implicit airbrake: AirbrakeNotifier, imageConfig: S3ImageConfig, pubIdConfig: PublicIdConfiguration): BatchFetchable[KeepActivity] = {

    import com.keepit.common.util.DescriptionElements._

    lazy val initialEventsBF = {
      val basicAuthorBF = sourceAttrOpt.map {
        case (sourceAttr, basicUserOpt) => BatchFetchable.trivial(BasicAuthor(sourceAttr, basicUserOpt))
      }.getOrElse {
        BatchFetchable.userOpt(keep.userId).map(_.map(BasicAuthor.fromUser).getOrElse(BasicAuthor.Fake))
      }
      val headerBF = sourceAttrOpt.map(_._1) match {
        case Some(KifiAttribution(keptBy, _, users, emails, libraries, _)) =>
          val nonKeeperRecipients = users.filter(_.externalId != keptBy.externalId)
          val recipientsElement = DescriptionElements.unwordsPretty(Seq(nonKeeperRecipients.map(fromBasicUser).toSeq, emails.map(e => fromText(e.address)).toSeq, libraries.map(fromBasicLibrary).toSeq).flatten)
          val actionElement = if (recipientsElement.flatten.nonEmpty) DescriptionElements("sent this to", recipientsElement) else DescriptionElements("sent this")

          basicAuthorBF.map(ba => DescriptionElements(ba, actionElement))
        case _ =>
          val (firstLibrary, orgOpt) = (BatchFetchable.libraryOpt(ktls.headOption.map(_.libraryId)), BatchFetchable.orgOpt(ktls.headOption.flatMap(_.organizationId)))
          (basicAuthorBF and firstLibrary and orgOpt).tupled.map {
            case (ba, fl, oo) => DescriptionElements(
              ba, "sent this",
              fl.map(lib => DescriptionElements("to", lib)),
              oo.map(org => DescriptionElements("in", org))
            )
          }
      }

      val noteBody = sourceAttrOpt.flatMap {
        case (ka: KifiAttribution, _) => keep.note.filter(_.nonEmpty)
        case (SlackAttribution(msg, _), _) => Some(msg.text.trim).filterNot { str =>
          val textIsLiterallyJustTheUrl = str == s"<${keep.url}>"
          textIsLiterallyJustTheUrl
        }
        case (TwitterAttribution(tweet), _) => Some(tweet.text).filter(_ != keep.url)
      }

      val source = sourceAttrOpt.flatMap { case (attr, _) => BasicKeepEventSource.fromSourceAttribution(attr) }

      (basicAuthorBF and headerBF).tupled.map {
        case (basicAuthor, header) =>
          val noteEvent = noteBody.map { note =>
            BasicKeepEvent(
              id = BasicKeepEventId.NoteId(Keep.publicId(keep.id.get)),
              author = basicAuthor,
              KeepEventKind.Note,
              header = DescriptionElements(basicAuthor),
              body = DescriptionElements(note),
              timestamp = keep.keptAt,
              source = source)
          }
          val initialEvent = BasicKeepEvent(
            id = BasicKeepEventId.InitialId(Keep.publicId(keep.id.get)),
            author = basicAuthor,
            KeepEventKind.Initial,
            header = header,
            body = DescriptionElements(),
            timestamp = keep.keptAt,
            source = source)

          noteEvent.toSeq :+ initialEvent
      }
    }

    val basicEventsBF = {
      val keepEvents = events.map(event => generateKeepEvent(keep.id.get, event))
      val comments = discussionOpt.map(_.messages.flatMap { msg =>
        msg.sentBy.map { sender =>
          val myAuthor = sender.fold(u => BatchFetchable.user(u).map(_.map(BasicAuthor.fromUser).getOrElse(BasicAuthor.Fake)), nu => BatchFetchable.trivial(BasicAuthor.fromNonUser(nu)))
          myAuthor.map { author =>
            BasicKeepEvent.generateCommentEvent(
              id = Message.publicId(msg.id),
              author = author,
              text = msg.text,
              sentAt = msg.sentAt,
              source = msg.source
            )
          }
        }
      }).getOrElse(Seq.empty)

      (initialEventsBF and BatchFetchable.seq(keepEvents ++ comments)).tupled.map {
        case (initialEvents, regularEvents) =>
          val subsequentEvents = regularEvents.sortBy(_.timestamp.getMillis)(Ord.descending).take(maxEvents)
          if (subsequentEvents.size < maxEvents) subsequentEvents ++ initialEvents else subsequentEvents
      }
    }

    val latestEventBF = (initialEventsBF and basicEventsBF).tupled.map {
      case (initialEvents, basicEvents) =>
        val lastEvent = basicEvents.headOption.getOrElse(initialEvents.head)
        val newHeader = lastEvent.kind match {
          case KeepEventKind.Initial => DescriptionElements(lastEvent.author, "sent this")
          case KeepEventKind.Note => DescriptionElements(lastEvent.author, "commented on this") // NB(ryan): we are pretending that notes are comments
          case KeepEventKind.Comment => DescriptionElements(lastEvent.author, "commented on this")
          case KeepEventKind.EditTitle => DescriptionElements(lastEvent.author, "edited the title")
          case KeepEventKind.ModifyRecipients => DescriptionElements(lastEvent.author, "sent this")
        }
        lastEvent.withHeader(newHeader)
    }

    (latestEventBF and basicEventsBF).tupled.map {
      case (latestEvent, basicEvents) => KeepActivity(latestEvent, basicEvents, numComments = discussionOpt.map(_.numMessages).getOrElse(0) + keep.note.size)
    }
  }

  def generateKeepEvent(keepId: Id[Keep], event: KeepEvent)(implicit imageConfig: S3ImageConfig, idConfig: PublicIdConfiguration, airbrake: AirbrakeNotifier): BatchFetchable[BasicKeepEvent] = {
    val publicEventId = CommonKeepEvent.publicId(KeepEvent.toCommonId(event.id.get))
    val (addedByBF, kind, headerBF) = event.eventData match {
      case ModifyRecipients(adder, diff) =>
        val basicAddedByBF = BatchFetchable.user(adder)
        val headerBF = {
          val actionElementBF = Seq(
            specialCaseForMovedLibrary(diff)
          ).flatten.headOption.getOrElse {
              val KeepRecipientsDiff(users, libraries, emails) = diff
              val addedUserElementsBF = BatchFetchable.seq(users.added.toSeq.map(BatchFetchable.user)).map(_.flatten.map(fromBasicUser))
              val addedLibElementsBF = BatchFetchable.seq(libraries.added.toSeq.map(BatchFetchable.library)).map(_.flatten.map(fromBasicLibrary))
              val addedEmailElements = emails.added.toSeq.map(email => fromText(email.address))
              (addedUserElementsBF and addedLibElementsBF).tupled.map {
                case (addedUserElements, addedLibElements) =>
                  val addedEntities = addedUserElements ++ addedLibElements ++ addedEmailElements
                  DescriptionElements("sent this to", unwordsPretty(addedEntities))
              }
            }
          val userElementBF = basicAddedByBF.map { basicAddedBy => basicAddedBy.map(fromBasicUser).getOrElse(fromText("Someone")) }
          (userElementBF and actionElementBF).tupled.map {
            case (userElement, actionElement) => DescriptionElements(userElement, actionElement)
          }
        }
        (basicAddedByBF, KeepEventKind.ModifyRecipients, headerBF)

      case EditTitle(editedBy, _, updated) =>
        val basicAddedByBF = BatchFetchable.user(editedBy)
        val headerBF = basicAddedByBF.map { basicAddedBy =>
          DescriptionElements(basicAddedBy.map(generateUserElement(_, fullName = true)).getOrElse(fromText("Someone")), "edited the title", updated.map(DescriptionElements("to", _)))
        }

        (basicAddedByBF, KeepEventKind.EditTitle, headerBF)
    }

    (addedByBF and headerBF).tupled.map {
      case (addedBy, header) =>
        BasicKeepEvent(
          id = BasicKeepEventId.fromEvent(publicEventId),
          author = addedBy.map(BasicAuthor.fromUser).getOrElse(BasicAuthor.Fake),
          kind = kind,
          header = header,
          body = DescriptionElements(),
          timestamp = event.eventTime,
          source = event.source.map(src => BasicKeepEventSource(src, url = None))
        )
    }
  }

  private def specialCaseForMovedLibrary(diff: KeepRecipientsDiff): Option[BatchFetchable[DescriptionElements]] = {
    val KeepRecipientsDiff(users, libraries, emails) = diff
    if (users.isEmpty && emails.isEmpty && libraries.added.size == 1 && libraries.removed.size == 1) Some {
      val fromLib = BatchFetchable.library(libraries.removed.head).map(_ getOrElse BasicLibrary.fake)
      val toLib = BatchFetchable.library(libraries.added.head).map(_ getOrElse BasicLibrary.fake)
      (fromLib and toLib).tupled.map {
        case (from, to) => DescriptionElements("moved this from", from, "to", to)
      }
    }
    else None
  }
}
