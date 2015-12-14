package com.keepit.controllers.internal

import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.net.URI
import com.google.inject.Inject
import com.keepit.commanders._
import com.keepit.commanders.emails.EmailTemplateSender
import com.keepit.common.akka.SafeFuture
import com.keepit.common.controller.ShoeboxServiceController
import com.keepit.common.db.slick.Database
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.mail.template.EmailToSend
import com.keepit.common.mail.{ EmailAddress, ElectronicMail, LocalPostOffice }
import com.keepit.common.service.FortyTwoServices
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.store.{ S3ImageStore, ImageSize }
import com.keepit.common.time._
import com.keepit.model._
import com.keepit.notify.NotificationInfoModel
import com.keepit.notify.model.{ NotificationKind }
import com.keepit.rover.RoverServiceClient
import com.keepit.rover.model.BasicImages
import com.keepit.shoebox.model.ids.UserSessionExternalId
import com.keepit.normalizer._
import com.keepit.search.{ SearchConfigExperiment, SearchConfigExperimentRepo }
import com.keepit.slack.SlackCommander
import com.keepit.slack.models.{ SlackChannelId, SlackTeamId, SlackAccessToken }
import com.keepit.social._
import org.joda.time.DateTime
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json._
import play.api.mvc.Action
import securesocial.core.IdentityId

import scala.concurrent.Future
import scala.util.{ Try, Failure, Success }
import com.keepit.common.json.{ KeyFormat, TraversableFormat, EitherFormat, TupleFormat }

class ShoeboxController @Inject() (
  db: Database,
  userConnectionRepo: UserConnectionRepo,
  userRepo: UserRepo,
  keepRepo: KeepRepo,
  keepCommander: KeepCommander,
  normUriRepo: NormalizedURIRepo,
  normalizedURIInterner: NormalizedURIInterner,
  searchConfigExperimentRepo: SearchConfigExperimentRepo,
  probabilisticExperimentGeneratorRepo: ProbabilisticExperimentGeneratorRepo,
  userExperimentRepo: UserExperimentRepo,
  postOffice: LocalPostOffice,
  airbrake: AirbrakeNotifier,
  keepDecorator: KeepDecorator,
  keepSourceCommander: KeepSourceCommander,
  keepImageCommander: KeepImageCommander,
  basicUserRepo: BasicUserRepo,
  socialUserInfoRepo: SocialUserInfoRepo,
  socialConnectionRepo: SocialConnectionRepo,
  sessionRepo: UserSessionRepo,
  searchFriendRepo: SearchFriendRepo,
  emailAddressRepo: UserEmailAddressRepo,
  libraryAccessCommander: LibraryAccessCommander,
  friendRequestRepo: FriendRequestRepo,
  invitationRepo: InvitationRepo,
  userValueRepo: UserValueRepo,
  orgMembershipRepo: OrganizationMembershipRepo,
  userCommander: UserCommander,
  kifiInstallationRepo: KifiInstallationRepo,
  socialGraphPlugin: SocialGraphPlugin,
  rawKeepImporterPlugin: RawKeepImporterPlugin,
  userInteractionCommander: UserInteractionCommander,
  libraryCommander: LibraryCommander,
  libraryRepo: LibraryRepo,
  libraryMembershipRepo: LibraryMembershipRepo,
  emailTemplateSender: EmailTemplateSender,
  newKeepsInLibraryCommander: NewKeepsInLibraryCommander,
  libraryInfoCommander: LibraryInfoCommander,
  libraryCardCommander: LibraryCardCommander,
  libraryMembershipCommander: LibraryMembershipCommander,
  organizationInviteCommander: OrganizationInviteCommander,
  organizationMembershipCommander: OrganizationMembershipCommander,
  s3ImageStore: S3ImageStore,
  organizationInfoCommander: OrganizationInfoCommander,
  userPersonaRepo: UserPersonaRepo,
  orgCandidateRepo: OrganizationMembershipCandidateRepo,
  permissionCommander: PermissionCommander,
  userIdentityHelper: UserIdentityHelper,
  rover: RoverServiceClient,
  slackCommander: SlackCommander,
  implicit val config: PublicIdConfiguration)(implicit private val clock: Clock)
    extends ShoeboxServiceController with Logging {

  val MaxContentLength = 6000

  def getUserIdentity(providerId: String, id: String) = Action { request =>
    val identityId = IdentityId(providerId = providerId, userId = id)
    val identity = db.readOnlyMaster { implicit session => userIdentityHelper.getUserIdentity(identityId) }
    Ok(Json.toJson(identity))
  }

  def getUserIdentityByUserId(userId: Id[User]) = Action { request =>
    val identity = db.readOnlyMaster { implicit session => userIdentityHelper.getUserIdentityByUserId(userId) }
    Ok(Json.toJson(identity))
  }

  def getUserOpt(id: ExternalId[User]) = Action { request =>
    val userOpt = db.readOnlyReplica { implicit s => userRepo.getOpt(id) } //using cache
    userOpt match {
      case Some(user) => Ok(Json.toJson(user))
      case None => Ok(JsNull)
    }
  }

  def getSocialUserInfosByUserId(userId: Id[User]) = Action {
    val sui = db.readOnlyReplica { implicit session =>
      socialUserInfoRepo.getByUser(userId) //using cache
    }
    Ok(Json.toJson(sui))
  }

  def getPrimaryOrg(userId: Id[User]) = Action {
    val orgIdOpt = db.readOnlyReplica { implicit session =>
      organizationMembershipCommander.getPrimaryOrganizationForUser(userId)
    }
    orgIdOpt match {
      case Some(orgId) => Ok(Json.toJson(orgId))
      case None => Ok(JsNull)
    }
  }

  def sendMail = Action(parse.tolerantJson(maxLength = 1024 * 500)) { request =>
    Json.fromJson[ElectronicMail](request.body).asOpt match {
      case Some(mail) =>
        db.readWrite(attempts = 3) { implicit session =>
          postOffice.sendMail(mail)
        }
        Ok("true")
      case None =>
        val e = new Exception("Unable to parse email")
        airbrake.notify(s"Unable to parse: ${request.body}", e)
        Ok("false")
    }
  }
  def processAndSendMail = Action.async(parse.tolerantJson(maxLength = 1024 * 500)) { request =>
    request.body.asOpt[EmailToSend] match {
      case Some(module) =>
        emailTemplateSender.send(module).map { mail =>
          Ok(if (mail.isReadyToSend) "true" else "false")
        }
      case None =>
        val e = new Exception("Unable to parse EmailToSend")
        airbrake.notify(s"Unable to parse: ${request.body}", e)
        Future.successful(Ok("false"))
    }
  }

  def getNormalizedURI(id: Id[NormalizedURI]) = SafeAsyncAction {
    val uri = db.readOnlyMaster { implicit s =>
      normUriRepo.get(id) //using cache
    }
    Ok(Json.toJson(uri))
  }

  def getNormalizedURIExternalIDs(ids: String) = SafeAsyncAction { request =>
    val uriIds = ids.split(',').map(id => Id[NormalizedURI](id.toLong))
    val uris = db.readOnlyMaster { implicit s => uriIds.map(id => normUriRepo.get(id).externalId) } //using cache
    Ok(Json.toJson(uris))
  }

  def getNormalizedURIByURL() = SafeAsyncAction(parse.tolerantJson(maxLength = MaxContentLength)) { request =>
    val url: String = Json.fromJson[String](request.body).get
    val uriOpt = db.readOnlyMaster { implicit s =>
      normalizedURIInterner.getByUri(url) //using cache
    }
    uriOpt match {
      case Some(uri) => Ok(Json.toJson(uri))
      case None => Ok(JsNull)
    }
  }

  def getNormalizedUriByUrlOrPrenormalize() = SafeAsyncAction(parse.tolerantJson(maxLength = MaxContentLength)) { request =>
    val url = Json.fromJson[String](request.body).get
    val normalizedUriOrPrenormStr = db.readOnlyMaster { implicit s => //using cache
      normalizedURIInterner.getByUriOrPrenormalize(url) match {
        case Success(Right(prenormalizedUrl)) => Json.obj("url" -> prenormalizedUrl)
        case Success(Left(nuri)) => Json.obj("normalizedURI" -> nuri)
        case Failure(ex) =>
          log.error("Could not get normalized uri or prenormalized url", ex)
          Json.obj("url" -> url)
      }
    }
    Ok(normalizedUriOrPrenormStr)
  }

  def internNormalizedURI() = SafeAsyncAction(parse.tolerantJson(maxLength = MaxContentLength)) { request =>
    val o = request.body.as[JsObject]
    val url = (o \ "url").as[String]
    if (URI.parse(url).isFailure) throw new Exception(s"when calling internNormalizedURI - can't parse url: $url")
    val contentWanted = (o \ "contentWanted").asOpt[Boolean] getOrElse false
    val uri = db.readWrite { implicit s => //using cache
      normalizedURIInterner.internByUri(url, contentWanted, NormalizationCandidate.fromJson(o))
    }
    if (contentWanted) { rover.fetchAsap(uri.id.get, uri.url) }
    Ok(Json.toJson(uri))
  }

  def getBookmarkByUriAndUser(uriId: Id[NormalizedURI], userId: Id[User]) = Action { request =>
    val bookmark = db.readOnlyMaster { implicit session => //using cache
      keepRepo.getByUriAndUser(uriId, userId)
    }.map(Json.toJson(_)).getOrElse(JsNull)
    Ok(bookmark)
  }

  def getUsers(ids: String) = Action { request =>
    val userIds = ids.split(',').map(id => Id[User](id.toLong))
    val users = db.readOnlyMaster { implicit s => userIds map userRepo.get } //using cache
    Ok(Json.toJson(users))
  }

  def getUserIdsByExternalIds() = Action(parse.tolerantJson) { request =>
    implicit val extIdToIdMapFormat = TraversableFormat.mapFormat[ExternalId[User], Id[User]](_.id, s => Try(ExternalId[User](s)).toOption)
    val extUserIds = request.body.as[Set[ExternalId[User]]]
    val idMap = db.readOnlyMaster { implicit s => userRepo.convertExternalIds(extUserIds) }
    Ok(Json.toJson(idMap))
  }

  def getBasicUsers() = Action(parse.tolerantJson) { request =>
    val userIds = request.body.as[Set[Id[User]]]
    val basicUsers = db.readOnlyMaster { implicit s => //using cache
      basicUserRepo.loadAll(userIds)
    }
    implicit val tupleWrites = TupleFormat.tuple2Writes[Id[User], BasicUser]
    val result = Json.toJson(basicUsers.toSeq)
    Ok(result)
  }

  def getEmailAddressesForUsers() = Action(parse.tolerantJson) { request =>
    val userIds = request.body.as[JsArray].value.map { x => Id[User](x.as[Long]) }
    val emails = db.readOnlyReplica(2) { implicit s =>
      userIds.map { userId => userId.id.toString -> emailAddressRepo.getAllByUser(userId).map { _.address } }.toMap
    }
    val json = Json.toJson(emails)
    log.debug(s"json emails for users [$userIds] are $json")
    Ok(json)
  }

  def getEmailAddressForUsers() = Action(parse.tolerantJson) { request =>
    Json.fromJson[Set[Id[User]]](request.body).fold(
      invalid = { jsErr =>
        airbrake.notify("s[getPrimaryEmailAddressForUsers] failed to deserialize request body to Seq[Id[User]")
        log.error(s"[getPrimaryEmailAddressForUsers] bad request: ${request.body}")
        BadRequest
      },
      valid = { userIds =>
        val userEmailMap = db.readOnlyReplica(2) { implicit session =>
          userIds.map { userId =>
            userId -> Try(emailAddressRepo.getByUser(userId)).toOption
          } toMap
        }
        Ok(Json.toJson(userEmailMap))
      }
    )
  }

  // on kifi
  def getConnectedUsers(id: Id[User]) = Action { request =>
    val ids = db.readOnlyMaster { implicit s => //using cache
      userConnectionRepo.getConnectedUsers(id).toSeq
        .map { friendId => JsNumber(friendId.id) }
    }
    Ok(JsArray(ids))
  }

  def getActiveExperiments = Action { request =>
    val exp = db.readOnlyMaster { implicit s => searchConfigExperimentRepo.getActive() } //using cache
    Ok(Json.toJson(exp))
  }

  def getExperiments = Action { request =>
    val exp = db.readOnlyReplica(2) { implicit s => searchConfigExperimentRepo.getNotInactive() }
    Ok(Json.toJson(exp))
  }

  def getExperiment(id: Id[SearchConfigExperiment]) = Action { request =>
    val exp = db.readOnlyReplica(2) { implicit s => searchConfigExperimentRepo.get(id) } //no cache used
    Ok(Json.toJson(exp))
  }

  def saveExperiment = Action(parse.tolerantJson) { request =>
    val exp = Json.fromJson[SearchConfigExperiment](request.body).get
    val saved = db.readWrite(attempts = 3) { implicit s => searchConfigExperimentRepo.save(exp) }
    Ok(Json.toJson(saved))
  }

  def getUserExperiments(userId: Id[User]) = Action { request =>
    val experiments = db.readOnlyMaster { implicit s => //using cache
      userExperimentRepo.getUserExperiments(userId).map(_.value)
    }
    Ok(Json.toJson(experiments))
  }

  def getExperimentsByUserIds() = Action(parse.tolerantJson) { request =>
    val userIds = request.body.as[JsArray].value.map { x => Id[User](x.as[Long]) }
    val exps = db.readOnlyMaster { implicit s => //using cache
      userIds.map { uid =>
        uid.id.toString -> userExperimentRepo.getUserExperiments(uid)
      }.toMap
    }
    Ok(Json.toJson(exps))
  }

  def getExperimentGenerators() = Action { request =>
    val result = db.readOnlyReplica { implicit session => probabilisticExperimentGeneratorRepo.allActive() }
    Ok(Json.toJson(result))
  }

  def getUsersByExperiment(experiment: UserExperimentType) = Action { request =>
    val users = db.readOnlyMaster { implicit s =>
      val userIds = userExperimentRepo.getUserIdsByExperiment(experiment)
      userRepo.getUsers(userIds).map(_._2)
    }
    Ok(Json.toJson(users))
  }

  def getSessionViewByExternalId(sessionId: UserSessionExternalId) = Action { request =>
    val res = db.readOnlyMaster { implicit session => //using cache
      sessionRepo.getViewOpt(sessionId)
    }
    Ok(Json.toJson(res))
  }

  def searchFriends(userId: Id[User]) = Action { request =>
    db.readOnlyMaster { implicit s => //using cache
      Ok(Json.toJson(searchFriendRepo.getSearchFriends(userId).map(_.id)))
    }
  }

  def getUnfriends(userId: Id[User]) = Action { request =>
    db.readOnlyReplica { implicit s =>
      Ok(Json.toJson(searchFriendRepo.getUnfriends(userId).map(_.id)))
    }
  }

  def getFriendRequestsRecipientIdBySender(senderId: Id[User]) = Action { request =>
    val requests = db.readOnlyReplica(2) { implicit s =>
      friendRequestRepo.getBySender(senderId).map(_.recipientId)
    }
    Ok(JsArray(requests.map { x => Json.toJson(x) }))
  }

  def setUserValue(userId: Id[User], key: UserValueName) = SafeAsyncAction(parse.tolerantJson) { request =>
    val value = request.body.as[String]
    db.readWrite(attempts = 3) { implicit session => userValueRepo.setValue(userId, key, value) }
    Ok
  }

  def getUserValue(userId: Id[User], key: UserValueName) = SafeAsyncAction { request =>
    val value = db.readOnlyMaster { implicit session => userValueRepo.getValueStringOpt(userId, key) } //using cache
    Ok(Json.toJson(value))
  }

  def getUserSegment(userId: Id[User]) = SafeAsyncAction { request =>
    val segment = userCommander.getUserSegment(userId)
    Ok(Json.toJson(segment))
  }

  def getExtensionVersion(installationId: ExternalId[KifiInstallation]) = SafeAsyncAction { request =>
    val version = db.readOnlyReplica(2) { implicit session => kifiInstallationRepo.get(installationId).version.toString }
    Ok(JsString(version))
  }

  def triggerRawKeepImport() = Action { request =>
    rawKeepImporterPlugin.processKeeps(broadcastToOthers = false)
    Status(202)("0")
  }

  def triggerSocialGraphFetch(socialUserInfoId: Id[SocialUserInfo]) = Action.async { request =>
    val socialUserInfo = db.readOnlyMaster { implicit session =>
      socialUserInfoRepo.get(socialUserInfoId)
    }
    socialGraphPlugin.asyncFetch(socialUserInfo, broadcastToOthers = false).map { _ =>
      Ok("0")
    }
  }

  def getUserImageUrl(id: Id[User], width: Int) = Action.async { request =>
    s3ImageStore.getPictureUrl(width, id).map { url =>
      Ok(Json.toJson(url))
    }
  }

  def getLapsedUsersForDelighted(maxCount: Int, skipCount: Int, after: DateTime, before: Option[DateTime]) = Action { request =>
    val userInfos = db.readOnlyMaster { implicit session =>
      userRepo.getUsers(userValueRepo.getLastActive(after, before, maxCount, skipCount)) map {
        case (userId, user) =>
          val emailAddress = Try(emailAddressRepo.getByUser(userId)).toOption
          DelightedUserRegistrationInfo(userId, user.externalId, emailAddress, user.fullName)
      }
    }
    Ok(Json.toJson(userInfos))
  }

  def getAllFakeUsers() = Action { request =>
    Ok(Json.toJson(userCommander.getAllFakeUsers()))
  }

  def getInvitations(senderId: Id[User]) = Action { request =>
    val invitations = db.readOnlyMaster { implicit session => invitationRepo.getBySenderId(senderId) }
    Ok(Json.toJson(invitations))
  }

  def getSocialConnections(userId: Id[User]) = Action { request =>
    val connectionsByNetwork = db.readOnlyMaster { implicit session => socialConnectionRepo.getSocialConnectionInfosByUser(userId) }
    val allConnections = connectionsByNetwork.valuesIterator.flatten.toSeq
    Ok(Json.toJson(allConnections))
  }

  def addInteractions(userId: Id[User]) = Action(parse.tolerantJson) { request =>
    val interactions = request.body.as[Seq[JsValue]].map { j =>
      val interaction = UserInteraction.getAction((j \ "action").as[String])
      (j \ "user").asOpt[Id[User]] match {
        case Some(id) => (UserInteractionRecipient(id), interaction)
        case None => (EmailInteractionRecipient((j \ "email").as[EmailAddress]), interaction)
      }
    }
    userInteractionCommander.addInteractions(userId, interactions)
    Ok
  }

  def canViewLibrary() = Action(parse.tolerantJson) { request =>
    val json = request.body
    val libraryId = (json \ "libraryId").as[Id[Library]]
    val userIdOpt = (json \ "userId").asOpt[Id[User]]
    val authToken = (json \ "authToken").asOpt[String]
    val authorized = libraryAccessCommander.canViewLibrary(userIdOpt, libraryId, authToken)
    Ok(JsBoolean(authorized))
  }

  def newKeepsInLibraryForEmail(userId: Id[User], max: Int) = Action { request =>
    val keeps = newKeepsInLibraryCommander.getLastEmailViewedKeeps(userId, max)
    libraryCommander.updateLastEmailSent(userId, keeps)
    Ok(Json.toJson(keeps))
  }

  def getPersonalKeeps(userId: Id[User]) = Action(parse.tolerantJson) { request =>
    val uriIds = request.body.as[Set[Id[NormalizedURI]]]
    val keepDataByUriId = keepDecorator.getPersonalKeeps(userId, uriIds)
    implicit val tupleWrites = TupleFormat.tuple2Writes[Id[NormalizedURI], Set[PersonalKeep]]
    val result = Json.toJson(keepDataByUriId.toSeq)
    Ok(result)
  }

  def getBasicKeepsByIds() = Action(parse.tolerantJson) { request =>
    val keepIds = request.body.as[Set[Id[Keep]]]
    val keepDataById = keepCommander.getBasicKeeps(keepIds)
    Ok(Json.toJson(keepDataById))
  }

  def getCrossServiceKeepsByIds() = Action(parse.tolerantJson) { request =>
    val keepIds = request.body.as[Set[Id[Keep]]]
    val keepDataById = keepCommander.getCrossServiceKeeps(keepIds)
    Ok(Json.toJson(keepDataById))
  }

  def getDiscussionKeepsByIds() = Action.async(parse.tolerantJson) { request =>
    implicit val payloadFormat = KeyFormat.key2Format[Id[User], Set[Id[Keep]]]("viewerId", "keepIds")
    val (viewerId, keepIds) = request.body.as[(Id[User], Set[Id[Keep]])]

    val keepById = db.readOnlyReplica { implicit s => keepRepo.getByIds(keepIds) }
    val keepsSeq = keepIds.toSeq.flatMap(keepById.get)
    val keepInfosFut = keepDecorator.decorateKeepsIntoKeepInfos(
      Some(viewerId),
      showPublishedLibraries = true,
      keepsSeq = keepsSeq,
      idealImageSize = ProcessedImageSize.Medium.idealSize,
      sanitizeUrls = true
    )

    keepInfosFut.map { keepInfos =>
      val keepInfoById = (keepsSeq zip keepInfos).map {
        case (keep, keepInfo) => keep.id.get -> keepInfo.asDiscussionKeep
      }.toMap
      Ok(Json.toJson(keepInfoById))
    }
  }

  def getBasicLibraryDetails() = Action(parse.tolerantJson) { request =>
    val libraryIds = (request.body \ "libraryIds").as[Set[Id[Library]]]
    val idealImageSize = (request.body \ "idealImageSize").as[ImageSize]
    val viewerId = (request.body \ "viewerId").asOpt[Id[User]]

    val basicDetailsByLibraryId = libraryInfoCommander.getBasicLibraryDetails(libraryIds, idealImageSize, viewerId)
    implicit val tupleWrites = TupleFormat.tuple2Writes[Id[Library], BasicLibraryDetails]
    val result = Json.toJson(basicDetailsByLibraryId.toSeq)
    Ok(result)
  }

  def getLibraryCardInfos() = Action(parse.tolerantJson) { request =>
    val libraryIds = (request.body \ "libraryIds").as[Set[Id[Library]]]
    val idealImageSize = (request.body \ "idealImageSize").as[ImageSize]
    val viewerId = (request.body \ "viewerId").asOpt[Id[User]]

    val libraryCardInfosWithId = db.readOnlyReplica { implicit session =>
      val libraryById = libraryRepo.getActiveByIds(libraryIds)
      val libraries = libraryIds.flatMap(libraryById.get)
      val owners = basicUserRepo.loadAll(libraries.map(_.ownerId))

      val libSeq = libraries.toSeq
      val libraryCardInfos = libraryCardCommander.createLibraryCardInfos(libSeq, owners, viewerId, withFollowing = true, idealSize = idealImageSize)

      libSeq.map(_.id.get) zip libraryCardInfos
    }
    implicit val tupleWrites = TupleFormat.tuple2Writes[Id[Library], LibraryCardInfo]
    Ok(Json.toJson(libraryCardInfosWithId))
  }

  def getKeepCounts() = Action(parse.tolerantJson) { request =>
    val userIds = request.body.as[Set[Id[User]]]
    val keepCountsByUserId = db.readOnlyMaster { implicit session =>
      keepRepo.getCountByUsers(userIds)
    }
    implicit val tupleWrites = TupleFormat.tuple2Writes[Id[User], Int]
    val result = Json.toJson(keepCountsByUserId.toSeq)
    Ok(result)
  }

  def getKeepImages() = Action(parse.tolerantJson) { request =>
    val keepIds = request.body.as[Set[Id[Keep]]]
    val imagesByKeepId = keepImageCommander.getBasicImagesForKeeps(keepIds)
    implicit val tupleWrites = TupleFormat.tuple2Writes[Id[Keep], BasicImages]
    val result = Json.toJson(imagesByKeepId.toSeq)
    Ok(result)
  }

  def getLibrariesWithWriteAccess(userId: Id[User]) = Action { request =>
    val libraryIds = libraryMembershipCommander.getLibrariesWithWriteAccess(userId)
    Ok(Json.toJson(libraryIds))
  }

  def getUserActivePersonas(userId: Id[User]) = Action { request =>
    val model = db.readOnlyReplica { implicit s => userPersonaRepo.getUserActivePersonas(userId) }
    Ok(Json.toJson(model))
  }

  def getOrganizationMembers(orgId: Id[Organization]) = Action { request =>
    val memberIds = organizationMembershipCommander.getMemberIds(orgId)
    Ok(Json.toJson(memberIds))
  }

  def hasOrganizationMembership(orgId: Id[Organization], userId: Id[User]) = Action { request =>
    val hasMembership = organizationMembershipCommander.getMembership(orgId, userId).isDefined
    Ok(JsBoolean(hasMembership))
  }

  def getOrganizationInviteViews(orgId: Id[Organization]) = Action { request =>
    val inviteViews: Set[OrganizationInviteView] = organizationInviteCommander.getInvitesByOrganizationId(orgId).map(OrganizationInvite.toOrganizationInviteView)
    Ok(Json.toJson(inviteViews))
  }

  def getOrganizationsForUsers() = Action(parse.tolerantJson) { request =>
    val userIds = request.body.as[Set[Id[User]]]
    val orgIdsByUserId = organizationMembershipCommander.getAllForUsers(userIds).mapValues(_.map(_.organizationId))
    Ok(Json.toJson(orgIdsByUserId))
  }

  def getOrgTrackingValues(orgId: Id[Organization]) = Action { request =>
    Ok(Json.toJson(organizationInfoCommander.getOrgTrackingValues(orgId)))
  }

  def getBasicOrganizationsByIds() = Action(parse.tolerantJson) { request =>
    val orgIds = request.body.as[Set[Id[Organization]]]
    val basicOrgs = organizationInfoCommander.getBasicOrganizations(orgIds)
    Ok(Json.toJson(basicOrgs))
  }

  def getLibraryMembershipView(libraryId: Id[Library], userId: Id[User]) = Action { request =>
    val membershipOpt = db.readOnlyReplica { implicit session => libraryMembershipRepo.getWithLibraryIdAndUserId(libraryId, userId).map(_.toLibraryMembershipView) }
    Ok(Json.toJson(membershipOpt))
  }

  def getOrganizationUserRelationship(orgId: Id[Organization], userId: Id[User]) = Action { request =>
    val (membershipOpt, candidateOpt, permissions) = db.readOnlyReplica { implicit session =>
      val membershipOpt = orgMembershipRepo.getByOrgIdAndUserId(orgId, userId)
      val candidateOpt = orgCandidateRepo.getByUserAndOrg(userId, orgId)
      val permissions = permissionCommander.getOrganizationPermissions(orgId, Some(userId))
      (membershipOpt, candidateOpt, permissions)
    }
    val inviteOpt = organizationInviteCommander.getLastSentByOrganizationIdAndInviteeId(orgId, userId)
    Ok(Json.toJson(OrganizationUserRelationship(orgId, userId, membershipOpt.map(_.role), Some(permissions), inviteOpt.isDefined, candidateOpt.isDefined)))
  }

  def getUserPermissionsByOrgId() = Action(parse.tolerantJson) { request =>
    val orgIds = (request.body \ "orgIds").as[Set[Id[Organization]]]
    val userId = (request.body \ "userId").as[Id[User]]

    val permissionsByOrgId = db.readOnlyMaster { implicit session =>
      orgIds.map { orgId => orgId -> permissionCommander.getOrganizationPermissions(orgId, Some(userId)) }.toMap
    }

    Ok(Json.toJson(permissionsByOrgId))
  }

  def getIntegrationsBySlackChannel() = Action(parse.tolerantJson) { request =>
    val teamId = (request.body \ "teamId").as[SlackTeamId]
    val channelId = (request.body \ "channelId").as[SlackChannelId]
    val integrations = slackCommander.getIntegrationsBySlackChannel(teamId, channelId)
    Ok(Json.toJson(integrations))
  }

  def getSourceAttributionForKeeps() = Action(parse.tolerantJson) { request =>
    val keepIds = (request.body \ "keepIds").as[Set[Id[Keep]]]
    val attributions = db.readOnlyMaster { implicit session =>
      keepSourceCommander.getSourceAttributionForKeeps(keepIds)
    }
    implicit val writes = SourceAttribution.internalFormat
    Ok(Json.toJson(attributions))
  }
}
