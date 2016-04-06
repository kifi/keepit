package com.keepit.commanders.gen

import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.store.S3ImageConfig
import com.keepit.common.util.{ Ord, DescriptionElements, DescriptionElement }
import com.keepit.discussion.{ Message, CrossServiceKeepActivity }
import com.keepit.model.{ KeepRecipients, KeepEventSourceKind, BasicKeepEvent, KeepEventSource, KeepEventKind, KeepActivity, TwitterAttribution, SlackAttribution, BasicOrganization, BasicLibrary, Library, User, KeepToUser, KeepToLibrary, SourceAttribution, Keep }
import com.keepit.model.KeepEventData.{ AddRecipients, EditTitle }
import com.keepit.social.{ BasicUser, BasicAuthor }

object KeepActivityGen {
  def generateKeepActivity(
    keep: Keep, sourceAttrOpt: Option[(SourceAttribution, Option[BasicUser])], elizaActivity: Option[CrossServiceKeepActivity],
    ktls: Seq[KeepToLibrary], ktus: Seq[KeepToUser],
    userById: Map[Id[User], BasicUser], libById: Map[Id[Library], BasicLibrary], orgByLibraryId: Map[Id[Library], BasicOrganization],
    maxEvents: Int)(implicit airbrake: AirbrakeNotifier, imageConfig: S3ImageConfig, pubIdConfig: PublicIdConfiguration): KeepActivity = {
    import com.keepit.common.util.DescriptionElements._

    lazy val initialKeepEvent = {
      val sortedKtls = ktls.sortBy(_.addedAt.getMillis * -1)(Ord.descending)
      val sortedKtus = ktus.sortBy(_.addedAt.getMillis * -1)(Ord.descending)

      val basicAuthor = sourceAttrOpt.map {
        case (sourceAttr, basicUserOpt) => BasicAuthor(sourceAttr, basicUserOpt)
      }.orElse(keep.userId.flatMap(userById.get).map(BasicAuthor.fromUser))
      if (basicAuthor.isEmpty) airbrake.notify(s"[activityLog] can't generate author for keep ${keep.id.get}, keep.user = ${keep.userId}")
      val authorElement: DescriptionElement = basicAuthor.map(fromBasicAuthor).getOrElse(fromText("Someone"))

      val header = sortedKtls.headOption match {
        case Some(ktl) =>
          val library: DescriptionElement = libById.get(ktl.libraryId).map(fromBasicLibrary).getOrElse("a library")
          val orgOpt = orgByLibraryId.get(ktl.libraryId).map(fromBasicOrg)
          DescriptionElements(
            authorElement, "kept this into", library,
            orgOpt.map(org => DescriptionElements("in", org))
          )
        case None =>
          sortedKtus.headOption match {
            case None =>
              airbrake.notify(s"[activityLog] no ktu or ktls on ${keep.id.get}, can't generate initial keep event")
              DescriptionElements(authorElement, "kept this page")
            case Some(firstKtu) =>
              val firstMinute = firstKtu.addedAt.plusMinutes(1)
              val firstSentTo = sortedKtus.takeWhile(_.addedAt.getMillis <= firstMinute.getMillis)
                .collect { case ktu if !keep.userId.contains(ktu.userId) => userById.get(ktu.userId) }.flatten
              DescriptionElements(authorElement, "started a discussion", if (firstSentTo.nonEmpty) DescriptionElements("with", DescriptionElements.unwordsPretty(firstSentTo.map(fromBasicUser))) else "on this page")
          }
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

    val elizaEvents = elizaActivity.map(_.messages.flatMap { message =>
      val messageId = Message.publicId(message.id)
      message.sentBy match {
        case Some(userOrNonUser) =>
          import DescriptionElements._
          userOrNonUser.left.foreach { uid =>
            if (!userById.contains(uid)) airbrake.notify(s"[activityLog] no basic user stored for user $uid on keep ${keep.id.get}, message ${message.id}")
          }
          val userOpt = userOrNonUser.left.toOption.flatMap(userById.get)
          val msgAuthor = userOrNonUser.fold(
            userId => userOpt.map(BasicAuthor.fromUser).getOrElse { BasicAuthor.Fake },
            nonUser => BasicAuthor.fromNonUser(nonUser)
          )
          val authorElement: DescriptionElement = userOrNonUser.fold[Option[DescriptionElement]](_ => userOpt.map(fromBasicUser), nonUser => Some(nonUser.id)).getOrElse("Someone")
          Some(BasicKeepEvent(
            id = Some(messageId),
            author = msgAuthor,
            KeepEventKind.Comment,
            header = DescriptionElements(authorElement, "commented on this page"),
            body = DescriptionElements(message.text),
            timestamp = message.sentAt,
            source = KeepEventSourceKind.fromMessageSource(message.source).map(kind => KeepEventSource(kind, url = None))
          ))
        case None =>
          message.auxData match {
            case Some(AddRecipients(addedBy, KeepRecipients(addedLibs, addedEmails, addedUsers))) =>
              if (!userById.contains(addedBy)) airbrake.notify(s"[activityLog] no basic user stored for user $addedBy on keep ${keep.id.get}, message ${message.id}")

              val basicAddedBy = userById.get(addedBy)
              val basicAddedUsers = addedUsers.flatMap(userById.get)
              val basicAddedLibs = addedLibs.flatMap(libById.get)

              val addedEntities: Set[DescriptionElement] = Set(basicAddedUsers.map(fromBasicUser), basicAddedLibs.map(fromBasicLibrary), addedEmails.map(e => fromText(e.address))).flatten

              Some(BasicKeepEvent(
                id = Some(messageId),
                author = basicAddedBy.map(BasicAuthor.fromUser).getOrElse(BasicAuthor.Fake),
                KeepEventKind.AddRecipients,
                header = DescriptionElements(basicAddedBy.map(fromBasicUser).getOrElse(fromText("Someone")), "added", unwordsPretty(addedEntities.toSeq)),
                body = DescriptionElements(),
                timestamp = message.sentAt,
                source = KeepEventSourceKind.fromMessageSource(message.source).map(kind => KeepEventSource(kind, url = None))
              ))
            case Some(EditTitle(editedBy, original, updated)) =>
              if (!userById.contains(editedBy)) airbrake.notify(s"[activityLog] no basic user stored for user $editedBy on keep ${keep.id.get}, message ${message.id}")
              val basicAddedBy = userById.get(editedBy)

              Some(BasicKeepEvent(
                id = Some(messageId),
                author = basicAddedBy.map(BasicAuthor.fromUser).getOrElse(BasicAuthor.Fake),
                KeepEventKind.EditTitle,
                header = DescriptionElements(basicAddedBy.map(fromBasicUser).getOrElse(fromText("Someone")), "edited the title"),
                body = DescriptionElements(original, "--->", updated),
                timestamp = message.sentAt,
                source = KeepEventSourceKind.fromMessageSource(message.source).map(kind => KeepEventSource(kind, url = None))
              ))
            case dataOpt =>
              if (dataOpt.isEmpty) airbrake.notify(s"[activityLog] message ${message.id} has no .sentBy and .auxData=$dataOpt, can't generate event")
              None
          }
      }
    }).getOrElse(Seq.empty)

    val events = if (elizaEvents.size >= maxEvents) elizaEvents.take(maxEvents) else elizaEvents :+ initialKeepEvent

    val latestEvent = {
      val lastEvent = events.headOption.getOrElse(initialKeepEvent)
      val newHeader = lastEvent.kind match {
        case KeepEventKind.Initial => DescriptionElements(lastEvent.author, "sent this page")
        case KeepEventKind.Comment => DescriptionElements(lastEvent.author, "commented on this page")
        case KeepEventKind.EditTitle => DescriptionElements(lastEvent.author, "edited the title")
        case KeepEventKind.AddRecipients => DescriptionElements(lastEvent.author, "added recipients to this discussion")
      }
      lastEvent.withHeader(newHeader)
    }

    KeepActivity(
      latestEvent = latestEvent,
      events = events,
      numComments = elizaActivity.map(_.numComments).getOrElse(0) + keep.note.size)
  }
}
