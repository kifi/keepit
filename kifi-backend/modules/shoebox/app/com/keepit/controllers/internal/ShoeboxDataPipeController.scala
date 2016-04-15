package com.keepit.controllers.internal

import com.keepit.classify.{ NormalizedHostname, DomainInfo, DomainRepo, Domain }
import com.keepit.commanders.{ TagCommander, KeepCommander, Hashtags }
import com.keepit.common.akka.SafeFuture
import com.keepit.common.db.{ Id, SequenceNumber }
import com.keepit.common.service.RequestConsolidator
import org.joda.time.DateTime
import play.api.libs.json._
import com.google.inject.{ Inject }
import com.keepit.common.db.slick.Database
import play.api.mvc.Action
import com.keepit.model._
import com.keepit.common.controller.ShoeboxServiceController
import com.keepit.common.logging.Logging
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import com.keepit.common.core._

class DataPipelineExecutor(val context: ExecutionContext)

class ShoeboxDataPipeController @Inject() (
    db: Database,
    userRepo: UserRepo,
    normUriRepo: NormalizedURIRepo,
    keepRepo: KeepRepo,
    ktuRepo: KeepToUserRepo,
    ktlRepo: KeepToLibraryRepo,
    kteRepo: KeepToEmailRepo,
    sourceRepo: KeepSourceAttributionRepo,
    changedUriRepo: ChangedURIRepo,
    phraseRepo: PhraseRepo,
    userConnRepo: UserConnectionRepo,
    searchFriendRepo: SearchFriendRepo,
    socialConnectionRepo: SocialConnectionRepo,
    socialUserInfoRepo: SocialUserInfoRepo,
    emailAddressRepo: UserEmailAddressRepo,
    libraryRepo: LibraryRepo,
    libraryMembershipRepo: LibraryMembershipRepo,
    organizationRepo: OrganizationRepo,
    organizationMembershipRepo: OrganizationMembershipRepo,
    organizationMembershipCandidateRepo: OrganizationMembershipCandidateRepo,
    userIpAddressRepo: UserIpAddressRepo,
    domainRepo: DomainRepo,
    orgDomainOwnershipRepo: OrganizationDomainOwnershipRepo,
    tagCommander: TagCommander,
    keepCommander: KeepCommander, // just for autofixer, can be removed after notes are good
    executor: DataPipelineExecutor) extends ShoeboxServiceController with Logging {

  implicit val context = executor.context

  private[this] val consolidateGetIndexableUrisReq = new RequestConsolidator[(SequenceNumber[NormalizedURI], Int), Seq[IndexableUri]](ttl = 60 seconds)

  def getIndexableUris(seqNum: SequenceNumber[NormalizedURI], fetchSize: Int) = Action.async { request =>
    val future = consolidateGetIndexableUrisReq((seqNum, fetchSize)) { key =>
      SafeFuture {
        db.readOnlyReplica { implicit s =>
          normUriRepo.getIndexable(seqNum, fetchSize)
        }
      } map { uris => uris map { u => IndexableUri(u) } }
    }
    future.map { indexables =>
      if (indexables.isEmpty) consolidateGetIndexableUrisReq.remove((seqNum, fetchSize))
      Ok(Json.toJson(indexables))
    }
  }

  def getIndexableUrisWithContent(seqNum: SequenceNumber[NormalizedURI], fetchSize: Int) = Action.async { request =>
    SafeFuture {
      val uris = db.readOnlyReplica { implicit s =>
        normUriRepo.getIndexablesWithContent(seqNum, limit = fetchSize)
      }
      val indexables = uris map { u => IndexableUri(u) }
      Ok(Json.toJson(indexables))
    }
  }

  def getHighestUriSeq() = Action.async { request =>
    SafeFuture {
      val seq = db.readOnlyReplica { implicit s =>
        normUriRepo.getCurrentSeqNum()
      }
      Ok(SequenceNumber.format.writes(seq))
    }
  }

  def getPhrasesChanged(seqNum: SequenceNumber[Phrase], fetchSize: Int) = Action.async { request =>
    SafeFuture {
      val phrases = db.readOnlyReplica { implicit s =>
        phraseRepo.getPhrasesChanged(seqNum, fetchSize)
      }
      Ok(Json.toJson(phrases))
    }
  }

  private[this] val consolidateGetBookmarksChangedReq = new RequestConsolidator[(SequenceNumber[Keep], Int), Seq[Keep]](ttl = 60 seconds)

  def getBookmarksChanged(seqNum: SequenceNumber[Keep], fetchSize: Int) = Action.async { request =>
    val future = consolidateGetBookmarksChangedReq((seqNum, fetchSize)) { key =>
      db.readOnlyReplicaAsync { implicit session =>
        keepRepo.getBookmarksChanged(seqNum, fetchSize)
      }
    }
    future.map { bookmarks =>
      if (bookmarks.isEmpty) consolidateGetBookmarksChangedReq.remove((seqNum, fetchSize))
      Ok(Json.toJson(bookmarks))
    }
  }

  def getUserIndexable(seqNum: SequenceNumber[User], fetchSize: Int) = Action.async { request =>
    SafeFuture {
      val users = db.readOnlyReplica { implicit s =>
        userRepo.getUsersSince(seqNum, fetchSize)
      }
      Ok(JsArray(users.map { u => Json.toJson(u) }))
    }
  }

  def getNormalizedUriUpdates(lowSeq: SequenceNumber[ChangedURI], highSeq: SequenceNumber[ChangedURI]) = Action.async { request =>
    SafeFuture {
      val changes = db.readOnlyReplica { implicit s =>
        changedUriRepo.getChangesBetween(lowSeq, highSeq).map { change =>
          (change.oldUriId, normUriRepo.get(change.newUriId))
        }
      }
      val jsChanges = changes.map {
        case (id, uri) =>
          JsObject(List("id" -> JsNumber(id.id), "uri" -> Json.toJson(uri)))
      }
      Ok(JsArray(jsChanges))
    }
  }

  def getUserConnectionsChanged(seqNum: SequenceNumber[UserConnection], fetchSize: Int) = Action.async { request =>
    SafeFuture {
      val changes = db.readOnlyReplica { implicit s =>
        userConnRepo.getUserConnectionChanged(seqNum, fetchSize)
      }
      Ok(Json.toJson(changes))
    }
  }

  def getSearchFriendsChanged(seqNum: SequenceNumber[SearchFriend], fetchSize: Int) = Action.async { request =>
    SafeFuture {
      val changes = db.readOnlyReplica { implicit s =>
        searchFriendRepo.getSearchFriendsChanged(seqNum, fetchSize)
      }
      Ok(Json.toJson(changes))
    }
  }

  def getIndexableSocialConnections(seqNum: SequenceNumber[SocialConnection], fetchSize: Int) = Action.async { request =>
    SafeFuture {
      val indexableSocialConnections = db.readOnlyReplica { implicit session =>
        socialConnectionRepo.getConnAndNetworkBySeqNumber(seqNum, fetchSize).map {
          case (firstUserId, secondUserId, state, seq, networkType) =>
            IndexableSocialConnection(firstUserId, secondUserId, networkType, state, seq)
        }
      }
      val json = Json.toJson(indexableSocialConnections)
      Ok(json)
    }
  }

  def getIndexableSocialUserInfos(seqNum: SequenceNumber[SocialUserInfo], fetchSize: Int) = Action.async { request =>
    SafeFuture {
      val socialUserInfos = db.readOnlyReplica { implicit session => socialUserInfoRepo.getBySequenceNumber(seqNum, fetchSize) }
      val json = Json.toJson(socialUserInfos)
      Ok(json)
    }
  }

  def getEmailAccountUpdates(seqNum: SequenceNumber[EmailAccountUpdate], fetchSize: Int) = Action.async { request =>
    SafeFuture {
      val modifiedEmails = db.readOnlyReplica { implicit session => emailAddressRepo.getBySequenceNumber(SequenceNumber[UserEmailAddress](seqNum.value), fetchSize) }
      val updates = modifiedEmails.map { email =>
        EmailAccountUpdate(email.address, email.userId, email.verified, email.state == UserEmailAddressStates.INACTIVE, SequenceNumber(email.seq.value))
      }
      val json = Json.toJson(updates)
      Ok(json)
    }
  }

  def getLibraryMembership(id: Id[LibraryMembership]) = Action.async { request =>
    SafeFuture {
      val membership = db.readOnlyMaster { implicit session =>
        libraryMembershipRepo.get(id)
      }
      Ok(Json.toJson(membership))
    }
  }

  def getCrossServiceKeepsByIds() = Action(parse.tolerantJson) { request =>
    val ids = request.body.as[Set[Id[Keep]]]
    val (keeps, ktus, ktes, ktls) = db.readOnlyMaster { implicit s =>
      val keepsById = keepRepo.getActiveByIds(ids)
      val ktusByKeep = ktuRepo.getAllByKeepIds(keepsById.keySet)
      val ktlsByKeep = ktlRepo.getAllByKeepIds(keepsById.keySet)
      val ktesByKeep = kteRepo.getAllByKeepIds(keepsById.keySet)
      (keepsById, ktusByKeep, ktesByKeep, ktlsByKeep)
    }
    val keepDataById = keeps.map {
      case (keepId, keep) => keepId -> CrossServiceKeep.fromKeepAndRecipients(
        keep = keep,
        users = ktus.getOrElse(keepId, Seq.empty).map(_.userId).toSet,
        emails = ktes.getOrElse(keepId, Seq.empty).map(_.emailAddress).toSet,
        libraries = ktls.getOrElse(keepId, Seq.empty).map(CrossServiceKeep.LibraryInfo.fromKTL).toSet
      )
    }
    Ok(Json.toJson(keepDataById))
  }
  def getCrossServiceKeepsAndTagsChanged(seqNum: SequenceNumber[Keep], fetchSize: Int) = Action.async { request =>
    SafeFuture {
      val keepAndTagsChanged = db.readOnlyReplica { implicit session =>
        val changedKeeps = keepRepo.getBySequenceNumber(seqNum, fetchSize)
        val keepIds = changedKeeps.flatMap(_.id).toSet
        val attributionById = sourceRepo.getByKeepIds(keepIds)
        val ktlsByKeep = ktlRepo.getAllByKeepIds(keepIds)
        val ktusByKeep = ktuRepo.getAllByKeepIds(keepIds)
        val ktesByKeep = kteRepo.getAllByKeepIds(keepIds)
        val tagsByKeep = tagCommander.getTagsForKeeps(keepIds)
        changedKeeps.map { keep =>
          val csKeep = CrossServiceKeep.fromKeepAndRecipients(
            keep = keep,
            users = ktusByKeep.getOrElse(keep.id.get, Seq.empty).map(_.userId).toSet,
            emails = ktesByKeep.getOrElse(keep.id.get, Seq.empty).map(_.emailAddress).toSet,
            libraries = ktlsByKeep.getOrElse(keep.id.get, Seq.empty).map(CrossServiceKeep.LibraryInfo.fromKTL).toSet
          )

          val tags = tagsByKeep.getOrElse(keep.id.get, Seq.empty).toSet
          val tagsFromNote = Hashtags.findAllHashtagNames(keep.note.getOrElse("")).map(Hashtag.apply)
          val allTags = (tags ++ tagsFromNote).distinctBy(_.normalized)

          log.info(s"[getCrossServiceKeepsAndTagsChanged] ${keep.id.get}: $tags vs $tagsFromNote")

          val source = attributionById.get(keep.id.get)
          CrossServiceKeepAndTags(csKeep, source, allTags)
        }
      }
      Ok(Json.toJson(keepAndTagsChanged))
    }
  }
  def getKeepsAndTagsChanged(seqNum: SequenceNumber[Keep], fetchSize: Int) = Action.async { request =>
    SafeFuture {
      val keepAndTagsChanged = db.readOnlyReplica { implicit session =>
        val changedKeeps = keepRepo.getBySequenceNumber(seqNum, fetchSize)
        val keepIds = changedKeeps.flatMap(_.id).toSet
        val attributionById = sourceRepo.getByKeepIds(keepIds)
        val libByKeep = {
          ktlRepo.getAllByKeepIds(keepIds).flatMapValues(_.headOption.map(ktl => libraryRepo.get(ktl.libraryId)))
        }
        val tagsByKeepId = tagCommander.getForKeeps(keepIds)
        changedKeeps.map { keep =>
          val tags = tagsByKeepId.getOrElse(keep.id.get, Seq.empty).map(_.tag).toSet
          val noteTags = Hashtags.findAllHashtagNames(keep.note.getOrElse("")).map(Hashtag.apply)
          val allTags = (tags ++ noteTags).distinctBy(_.normalized)
          if (tags.map(_.normalized).toSet != noteTags.map(_.normalized)) {
            log.info(s"[getKeepsAndTagsChanged] ${keep.id.get}: ${tags} vs $noteTags")
          }
          val source = attributionById.get(keep.id.get)
          val lib = libByKeep.get(keep.id.get)
          KeepAndTags(keep, (lib.map(_.visibility).getOrElse(LibraryVisibility.SECRET), lib.flatMap(_.organizationId)), source, allTags)
        }
      }
      Ok(Json.toJson(keepAndTagsChanged))
    }
  }

  def getLibrariesChanged(seqNum: SequenceNumber[Library], fetchSize: Int) = Action.async { request =>
    SafeFuture {
      val libs = db.readOnlyReplica { implicit s => libraryRepo.getBySequenceNumber(seqNum, fetchSize) } map Library.toLibraryView
      Ok(Json.toJson(libs))
    }
  }

  def getDetailedLibrariesChanged(seqNum: SequenceNumber[Library], fetchSize: Int) = Action.async { request =>
    SafeFuture {
      val libs = db.readOnlyReplica { implicit s =>
        val libraries = libraryRepo.getBySequenceNumber(seqNum, fetchSize)
        libraries map { library => Library.toDetailedLibraryView(library) }
      }
      Ok(Json.toJson(libs))
    }
  }

  def getLibraryMembershipsChanged(seqNum: SequenceNumber[LibraryMembership], fetchSize: Int) = Action.async { request =>
    SafeFuture {
      val mem = db.readOnlyReplica { implicit s => libraryMembershipRepo.getBySequenceNumber(seqNum, fetchSize) } map {
        _.toLibraryMembershipView
      }
      Ok(Json.toJson(mem))
    }
  }

  def dumpLibraryURIIds(libId: Id[Library]) = Action.async { implicit request =>
    SafeFuture {
      val keeps = db.readOnlyReplica { implicit s => keepRepo.pageByLibrary(libId, offset = 0, limit = Integer.MAX_VALUE) }
      val ids = keeps.filter(_.state == KeepStates.ACTIVE).sortBy(-_.keptAt.getMillis).take(5000).map { _.uriId }
      Ok(Json.toJson(ids))
    }
  }

  def getIngestableOrganizations(seqNum: SequenceNumber[Organization], fetchSize: Int) = Action.async { request =>
    SafeFuture {
      val orgs = db.readOnlyReplica { implicit s => organizationRepo.getBySequenceNumber(seqNum, fetchSize) } map {
        _.toIngestableOrganization
      }
      Ok(Json.toJson(orgs))
    }
  }

  def getIngestableOrganizationMemberships(seqNum: SequenceNumber[OrganizationMembership], fetchSize: Int) = Action.async { request =>
    SafeFuture {
      val orgMems = db.readOnlyReplica { implicit s => organizationMembershipRepo.getBySequenceNumber(seqNum, fetchSize) } map {
        _.toIngestableOrganizationMembership
      }
      Ok(Json.toJson(orgMems))
    }
  }

  def getIngestableOrganizationMembershipCandidates(seqNum: SequenceNumber[OrganizationMembershipCandidate], fetchSize: Int) = Action.async { request =>
    SafeFuture {
      val orgMemCands = db.readOnlyReplica { implicit s => organizationMembershipCandidateRepo.getBySequenceNumber(seqNum, fetchSize) } map {
        _.toIngestableOrganizationMembershipCandidate
      }
      Ok(Json.toJson(orgMemCands))
    }
  }

  def getIngestableUserIpAddresses(seqNum: SequenceNumber[UserIpAddress], fetchSize: Int) = Action.async { request =>
    SafeFuture {
      val ipAddresses = db.readOnlyReplica { implicit s => userIpAddressRepo.getBySequenceNumber(seqNum, fetchSize) }.map {
        _.toIngestableUserIpAddress
      }
      Ok(Json.toJson(ipAddresses))
    }
  }

  def internDomainsByDomainNames() = Action.async(parse.json) { request =>
    SafeFuture {
      val domainNames = (request.body \ "domainNames").as[Set[String]].flatMap(str => NormalizedHostname.fromHostname(str))
      val domainInfoByName: Map[String, DomainInfo] = db.readWrite { implicit session =>
        domainRepo.internAllByNames(domainNames).map { case (hostname, domain) => hostname.value -> domain.toDomainInfo }
      }
      Ok(Json.toJson(domainInfoByName))
    }
  }

  def getIngestableOrganizationDomainOwnerships(seqNum: SequenceNumber[OrganizationDomainOwnership], fetchSize: Int) = Action.async { request =>
    SafeFuture {
      val orgDomainOwnerships = db.readOnlyReplica { implicit s =>
        orgDomainOwnershipRepo.getBySequenceNumber(seqNum, fetchSize).map { own =>
          own.toIngestableOrganizationDomainOwnership(domainRepo.get(own.normalizedHostname).flatMap(_.id).get)
        }
      }
      Ok(Json.toJson(orgDomainOwnerships))
    }
  }
}
