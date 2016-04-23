package com.keepit.commanders.gen

import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.core.regexExtensionOps
import com.keepit.common.store.S3ImageConfig
import com.keepit.common.util.DescriptionElements._
import com.keepit.common.util._
import com.keepit.model.BasicKeepEvent.BasicKeepEventId
import com.keepit.model.{ BasicKeepEventSource, CommonKeepEvent, KifiAttribution, KeepEvent, KeepRecipientsDiff, BasicKeepEvent, KeepEventKind, KeepActivity, TwitterAttribution, SlackAttribution, BasicOrganization, BasicLibrary, Library, User, KeepToUser, KeepToLibrary, SourceAttribution, Keep }
import com.keepit.discussion.{ Message, CrossServiceDiscussion, Discussion }
import com.keepit.model.KeepEventData.{ ModifyRecipients, EditTitle }
import com.keepit.social.{ BasicUser, BasicAuthor }

object KeepActivityGen {
  final case class SerializationInfo(
    userById: Map[Id[User], BasicUser],
    libById: Map[Id[Library], BasicLibrary],
    orgByLibraryId: Map[Id[Library], BasicOrganization])

  def generateKeepActivity(
    keep: Keep,
    sourceAttrOpt: Option[(SourceAttribution, Option[BasicUser])],
    events: Seq[KeepEvent],
    discussionOpt: Option[CrossServiceDiscussion],
    ktls: Seq[KeepToLibrary],
    ktus: Seq[KeepToUser],
    maxEvents: Int)(implicit info: SerializationInfo, airbrake: AirbrakeNotifier, imageConfig: S3ImageConfig, pubIdConfig: PublicIdConfiguration): KeepActivity = {

    import com.keepit.common.util.DescriptionElements._

    lazy val initialEvents = {
      val basicAuthor = sourceAttrOpt.map {
        case (sourceAttr, basicUserOpt) => BasicAuthor(sourceAttr, basicUserOpt)
      }.orElse(keep.userId.flatMap(info.userById.get).map(BasicAuthor.fromUser))
      if (basicAuthor.isEmpty) airbrake.notify(s"[activityLog] can't generate author for keep ${keep.id.get}, keep.user = ${keep.userId}")
      val authorElement: DescriptionElement = basicAuthor.map(fromBasicAuthor).getOrElse(fromText("Someone"))

      val (firstLibrary, orgOpt) = (ktls.headOption.flatMap(ktl => info.libById.get(ktl.libraryId)), ktls.headOption.flatMap(ktl => info.orgByLibraryId.get(ktl.libraryId)))

      val header = sourceAttrOpt.map(_._1) match {
        case Some(KifiAttribution(keptBy, _, users, emails, libraries, _)) =>
          val nonKeeperRecipients = users.filter(_.externalId != keptBy.externalId)
          val recipientsElement = DescriptionElements.unwordsPretty(Seq(nonKeeperRecipients.map(fromBasicUser).toSeq, emails.map(e => fromText(e.address)).toSeq, libraries.map(fromBasicLibrary).toSeq).flatten)
          val actionElement = if (recipientsElement.flatten.nonEmpty) DescriptionElements("sent this to", recipientsElement) else DescriptionElements("sent this")

          DescriptionElements(authorElement, actionElement)
        case _ =>
          DescriptionElements(
            authorElement, "sent this",
            firstLibrary.map(lib => DescriptionElements("to", lib)),
            orgOpt.map(org => DescriptionElements("in", org))
          )
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

      val noteEvent = noteBody.map { note =>
        BasicKeepEvent(
          id = BasicKeepEventId.NoteId(Keep.publicId(keep.id.get)),
          author = basicAuthor.getOrElse(BasicAuthor.Fake),
          KeepEventKind.Note,
          header = authorElement,
          body = DescriptionElements(note),
          timestamp = keep.keptAt,
          source = source)
      }
      val initialEvent = BasicKeepEvent(
        id = BasicKeepEventId.InitialId(Keep.publicId(keep.id.get)),
        author = basicAuthor.getOrElse(BasicAuthor.Fake),
        KeepEventKind.Initial,
        header = header,
        body = DescriptionElements(),
        timestamp = keep.keptAt,
        source = source)

      noteEvent.toSeq :+ initialEvent
    }

    val keepEvents = events.map(event => generateKeepEvent(keep.id.get, event))
    val comments = discussionOpt.map(_.messages.flatMap { msg =>
      for {
        sender <- msg.sentBy
        myauthor <- sender.fold(u => info.userById.get(u).map(BasicAuthor.fromUser), nu => Some(BasicAuthor.fromNonUser(nu)))
      } yield BasicKeepEvent.generateCommentEvent(
        id = Message.publicId(msg.id),
        author = myauthor,
        text = msg.text,
        sentAt = msg.sentAt,
        source = msg.source
      )
    }).getOrElse(Seq.empty)

    val basicEvents = {
      val subsequentEvents = (keepEvents ++ comments).sortBy(_.timestamp.getMillis)(Ord.descending).take(maxEvents)
      if (subsequentEvents.size < maxEvents) subsequentEvents ++ initialEvents else subsequentEvents
    }

    val latestEvent = {
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

    KeepActivity(latestEvent, basicEvents, numComments = discussionOpt.map(_.numMessages).getOrElse(0) + keep.note.size)
  }

  def generateKeepEvent(keepId: Id[Keep], event: KeepEvent)(implicit info: SerializationInfo, imageConfig: S3ImageConfig, idConfig: PublicIdConfiguration, airbrake: AirbrakeNotifier): BasicKeepEvent = {
    val publicEventId = CommonKeepEvent.publicId(KeepEvent.toCommonId(event.id.get))
    val (addedBy, kind, header) = event.eventData match {
      case ModifyRecipients(adder, diff) =>
        val basicAddedBy = {
          if (!info.userById.contains(adder)) airbrake.notify(s"[activityLog] no basic user stored for user $adder on keep $keepId, event ${event.id}")
          info.userById.get(adder)
        }
        val header = {
          val userElement = basicAddedBy.map(fromBasicUser).getOrElse(fromText("Someone"))
          val actionElement = Seq(
            specialCaseForMovedLibrary(diff)
          ).flatten.headOption.getOrElse {
              val KeepRecipientsDiff(users, libraries, emails) = diff
              val addedUserElements = users.added.flatMap(info.userById.get).map(fromBasicUser)
              val addedLibElements = libraries.added.flatMap(info.libById.get).map(fromBasicLibrary)
              val addedEmailElements = emails.added.map(email => fromText(email.address))
              val addedEntities: Seq[DescriptionElement] = (addedUserElements ++ addedLibElements ++ addedEmailElements).toSeq
              DescriptionElements("sent this to", unwordsPretty(addedEntities))
            }
          DescriptionElements(userElement, actionElement)
        }
        val body = DescriptionElements()

        (basicAddedBy, KeepEventKind.ModifyRecipients, header)

      case EditTitle(editedBy, _, updated) =>
        if (!info.userById.contains(editedBy)) airbrake.notify(s"[activityLog] no basic user stored for user $editedBy on keep $keepId, event ${event.id}")

        val basicAddedBy = info.userById.get(editedBy)
        val header = DescriptionElements(basicAddedBy.map(generateUserElement(_, fullName = true)).getOrElse(fromText("Someone")), "edited the title", updated.map(DescriptionElements("to", _)))

        (basicAddedBy, KeepEventKind.EditTitle, header)
    }

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

  private def specialCaseForMovedLibrary(diff: KeepRecipientsDiff)(implicit info: SerializationInfo): Option[DescriptionElements] = {
    val KeepRecipientsDiff(users, libraries, emails) = diff
    if (users.isEmpty && emails.isEmpty && libraries.added.size == 1 && libraries.removed.size == 1) for {
      fromLib <- info.libById.get(libraries.removed.head)
      toLib <- info.libById.get(libraries.added.head)
    } yield {
      DescriptionElements("moved this from", fromLib, "to", toLib)
    }
    else None
  }
}
