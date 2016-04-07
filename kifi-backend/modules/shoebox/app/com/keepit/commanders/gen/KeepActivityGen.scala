package com.keepit.commanders.gen

import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.store.S3ImageConfig
import com.keepit.common.util.{ DescriptionElements, DescriptionElement, ShowOriginalElement }
import com.keepit.model.{ KeepRecipientsDiff, KeepEventSourceKind, BasicKeepEvent, KeepEventSource, KeepEventKind, KeepActivity, TwitterAttribution, SlackAttribution, BasicOrganization, BasicLibrary, Library, User, KeepToUser, KeepToLibrary, SourceAttribution, Keep }
import com.keepit.discussion.Discussion
import com.keepit.model._
import com.keepit.model.KeepEventData.{ ModifyRecipients, EditTitle }
import com.keepit.social.{ BasicUser, BasicAuthor }

object KeepActivityGen {
  def generateKeepActivity(
    keep: Keep,
    sourceAttrOpt: Option[(SourceAttribution, Option[BasicUser])],
    events: Seq[KeepEvent],
    discussionOpt: Option[Discussion],
    ktls: Seq[KeepToLibrary],
    ktus: Seq[KeepToUser],
    userById: Map[Id[User], BasicUser],
    libById: Map[Id[Library], BasicLibrary],
    orgByLibraryId: Map[Id[Library], BasicOrganization],
    maxEvents: Int)(implicit airbrake: AirbrakeNotifier, imageConfig: S3ImageConfig, pubIdConfig: PublicIdConfiguration): KeepActivity = {

    import com.keepit.common.util.DescriptionElements._

    lazy val initialEvent = {
      val basicAuthor = sourceAttrOpt.map {
        case (sourceAttr, basicUserOpt) => BasicAuthor(sourceAttr, basicUserOpt)
      }.orElse(keep.userId.flatMap(userById.get).map(BasicAuthor.fromUser))
      if (basicAuthor.isEmpty) airbrake.notify(s"[activityLog] can't generate author for keep ${keep.id.get}, keep.user = ${keep.userId}")
      val authorElement: DescriptionElement = basicAuthor.map(fromBasicAuthor).getOrElse(fromText("Someone"))

      val (firstLibrary, orgOpt) = (ktls.headOption.flatMap(ktl => libById.get(ktl.libraryId)), ktls.headOption.flatMap(ktl => orgByLibraryId.get(ktl.libraryId)))

      val header = sourceAttrOpt.map(_._1) match {
        case Some(KifiAttribution(keptBy, note, users, emails, libraries, source)) =>
          val nonKeeperRecipients = users.filter(_.id != keptBy.externalId)
          val recipientsElement = DescriptionElements.unwordsPretty(Seq(nonKeeperRecipients.map(fromBasicUser).toSeq, emails.map(e => fromText(e.address)).toSeq, libraries.map(fromBasicLibrary).toSeq).flatten)
          val actionElement = if (recipientsElement.flatten.nonEmpty) DescriptionElements("added", recipientsElement, "to this discussion") else DescriptionElements("started a discussion on this page")

          DescriptionElements(authorElement, actionElement)
        case _ =>
          DescriptionElements(
            authorElement, "kept this",
            firstLibrary.map(lib => DescriptionElements("into", lib)),
            orgOpt.map(org => DescriptionElements("in", org))
          )
      }

      val source = sourceAttrOpt.map(_._1).collect {
        case SlackAttribution(message, _) => KeepEventSource(KeepEventSourceKind.Slack, Some(message.permalink))
        case TwitterAttribution(tweet) => KeepEventSource(KeepEventSourceKind.Twitter, Some(tweet.permalink))
      }

      val body = DescriptionElements(keep.note)
      BasicKeepEvent(
        id = None,
        author = basicAuthor.get,
        KeepEventKind.Initial,
        header = header,
        body = body,
        timestamp = keep.keptAt,
        source = source
      )
    }
    val keepEvents = events.map { event =>
      val publicEventId = CommonKeepEvent.publicId(KeepEvent.toCommonId(event.id.get))
      val (addedBy, kind, header, body) = event.eventData match {
        case ModifyRecipients(addedBy, KeepRecipientsDiff(users, libraries, emails)) =>
          if (!userById.contains(addedBy)) airbrake.notify(s"[activityLog] no basic user stored for user $addedBy on keep ${keep.id.get}, event ${event.id}")

          val basicAddedBy = userById.get(addedBy)
          val addedUserElements = users.added.flatMap(userById.get).map(fromBasicUser)
          val addedLibElements = libraries.added.flatMap(libById.get).map(fromBasicLibrary)
          val addedEmailElements = emails.added.map(email => fromText(email.address))
          val addedEntities: Seq[DescriptionElement] = (addedUserElements ++ addedLibElements ++ addedEmailElements).toSeq

          val header = DescriptionElements(basicAddedBy.map(fromBasicUser).getOrElse(fromText("Someone")), "added", unwordsPretty(addedEntities), "to this discussion")
          val body = DescriptionElements()

          (basicAddedBy, KeepEventKind.ModifyRecipients, header, body)

        case EditTitle(editedBy, original, updated) =>
          if (!userById.contains(editedBy)) airbrake.notify(s"[activityLog] no basic user stored for user $editedBy on keep ${keep.id.get}, event ${event.id}")

          val basicAddedBy = userById.get(editedBy)
          val header = DescriptionElements(basicAddedBy.map(fromBasicUser).getOrElse(fromText("Someone")), "edited the title")
          val body = DescriptionElements(ShowOriginalElement(original.getOrElse(""), updated.getOrElse("")))

          (basicAddedBy, KeepEventKind.EditTitle, header, body)
      }

      BasicKeepEvent(
        id = Some(Right(publicEventId)),
        author = addedBy.map(BasicAuthor.fromUser).getOrElse(BasicAuthor.Fake),
        kind = kind,
        header = header,
        body = body,
        timestamp = event.eventTime,
        source = event.source.map(src => KeepEventSource(src, url = None))
      )
    }

    val comments = discussionOpt.map(_.messages.map(BasicKeepEvent.fromMessage)).getOrElse(Seq.empty)

    val basicEvents = {
      val subsequentEvents = (keepEvents ++ comments).sortBy(_.timestamp.getMillis * -1).take(maxEvents)
      if (subsequentEvents.size < maxEvents) subsequentEvents :+ initialEvent else subsequentEvents
    }

    val latestEvent = {
      val lastEvent = basicEvents.headOption.getOrElse(initialEvent)
      val newHeader = lastEvent.kind match {
        case KeepEventKind.Initial => DescriptionElements(lastEvent.author, "sent this page")
        case KeepEventKind.Comment => DescriptionElements(lastEvent.author, "commented on this page")
        case KeepEventKind.EditTitle => DescriptionElements(lastEvent.author, "edited the title")
        case KeepEventKind.ModifyRecipients => DescriptionElements(lastEvent.author, "added recipients to this discussion")
      }
      lastEvent.withHeader(newHeader)
    }

    KeepActivity(
      latestEvent = latestEvent,
      events = basicEvents,
      numComments = discussionOpt.map(_.numMessages).getOrElse(0) + keep.note.size)
  }
}
