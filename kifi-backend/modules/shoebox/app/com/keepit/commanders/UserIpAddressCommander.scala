package com.keepit.commanders

import com.google.inject.Inject
import com.keepit.common.actor.ActorInstance
import com.keepit.common.akka.{ FortyTwoActor, UnsupportedActorMessage }
import com.keepit.common.cache.TransactionalCaching.Implicits._
import com.keepit.common.cache.{ CacheStatistics, FortyTwoCachePlugin, ImmutableJsonCacheImpl, Key }
import com.keepit.common.controller.UserRequest
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.{ AccessLog, Logging }
import com.keepit.common.mail.EmailAddress
import com.keepit.common.net.{ DirectUrl, HttpClient, UserAgent }
import com.keepit.common.service.IpAddress
import com.keepit.common.time._
import com.keepit.common.util.{ LinkElement, DescriptionElements }
import com.keepit.model._
import com.keepit.slack.{ InhouseSlackChannel, InhouseSlackClient }
import com.keepit.slack.models.SlackMessageRequest
import org.joda.time.{ DateTime, Period }
import play.api.libs.functional.syntax._
import play.api.libs.json._
import play.api.{ Mode, Play }

import scala.concurrent.duration.Duration
import scala.concurrent.{ Await, ExecutionContext, Future }
import scala.util.Try

case class RichIpAddress(ip: IpAddress, org: Option[String], country: Option[String], region: Option[String], city: Option[String],
    lat: Option[Double], lon: Option[Double], timezone: Option[String], zip: Option[String]) {
  def countryRegion = Seq(country, region).flatten.mkString(", ")
  def countryRegionCity = Seq(country, region, city).flatten.mkString(", ")
}

object RichIpAddress {
  implicit val format = (
    (__ \ 'ip).format[IpAddress] and
    (__ \ 'org).formatNullable[String] and
    (__ \ 'country).formatNullable[String] and
    (__ \ 'region).formatNullable[String] and
    (__ \ 'city).formatNullable[String] and
    (__ \ 'lat).formatNullable[Double] and
    (__ \ 'lon).formatNullable[Double] and
    (__ \ 'timezone).formatNullable[String] and
    (__ \ 'zip).formatNullable[String]
  )(RichIpAddress.apply, unlift(RichIpAddress.unapply))

  def apply(ip: IpAddress, json: JsValue): RichIpAddress = {
    (json \ "query").asOpt[String] foreach { parsed => assert(ip.ip == parsed, s"parsed ip from json $json does not equal [$ip]/[$parsed]") }
    RichIpAddress(
      ip,
      (json \ "org").asOpt[String].orElse((json \ "isp").asOpt[String]),
      (json \ "country").asOpt[String].orElse((json \ "countryCode").asOpt[String]), (json \ "regionName").asOpt[String].orElse((json \ "region").asOpt[String]), (json \ "city").asOpt[String],
      (json \ "lat").asOpt[Double], (json \ "lon").asOpt[Double],
      (json \ "timezone").asOpt[String], (json \ "zip").asOpt[String])
  }

  def empty(ip: IpAddress): RichIpAddress = RichIpAddress(ip, None, None, None, None, None, None, None, None)
}

object UserIpAddressRules {
  val blacklistCompanies = Set("Digital Ocean", "AT&T Wireless", "Verizon Wireless", "Best Buy Co.", "Leaseweb USA", "Nobis Technology Group, LLC",
    "San Francisco International Airport", "Nomad Digital", "Choopa, LLC", "Linode", "Voxility S.R.L.", "ServerStack").map(_.toLowerCase)
}

case class UserIpAddressEvent(userId: Id[User], ip: IpAddress, userAgent: UserAgent, reportNewClusters: Boolean = true) {
  if (ip.datacenterIp) {
    throw new IllegalArgumentException(s"IP Addresses of the form 10.x.x.x are internal ec2 addresses and should not be logged. User $userId, ip $ip, agent $userAgent")
  }
}

class UserIpAddressActor @Inject() (userIpAddressEventLogger: UserIpAddressEventLogger, airbrake: AirbrakeNotifier) extends FortyTwoActor(airbrake) {

  def receive = {
    case event: UserIpAddressEvent => // do nothing... //userIpAddressEventLogger.logUser(event)
    case m => throw new UnsupportedActorMessage(m)
  }
}

case class RichIpAddressKey(ip: IpAddress) extends Key[RichIpAddress] {
  override val version = 1
  val namespace = "rich_ip_address"
  def toKey(): String = ip.ip //funny, yea
}

class RichIpAddressCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends ImmutableJsonCacheImpl[RichIpAddressKey, RichIpAddress](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

class UserIpAddressEventLogger @Inject() (
    db: Database,
    userRepo: UserRepo,
    emailRepo: UserEmailAddressRepo,
    userIpAddressRepo: UserIpAddressRepo,
    userValueRepo: UserValueRepo,
    httpClient: HttpClient,
    slackClient: InhouseSlackClient,
    richIpAddressCache: RichIpAddressCache,
    organizationRepo: OrganizationRepo,
    organizationMembershipCandidateRepo: OrganizationMembershipCandidateRepo,
    organizationMembershipRepo: OrganizationMembershipRepo,
    implicit val executionContext: ExecutionContext,
    userCommander: UserCommander,
    clock: Clock) extends Logging {

  private val clusterMemoryTime = Period.days(2) // How long back do we look and still consider a user to be part of a cluster

  def logUser(event: UserIpAddressEvent): Unit = {
    val now = clock.now()
    val agentType = simplifyUserAgent(event.userAgent)
    if (agentType == "NONE") {
      log.info("[IPTRACK AGENT] Could not parse an agent type out of: " + event.userAgent)
    }

    val userIsFake = userCommander.getAllFakeUsers()(event.userId)

    val (cluster, ignoreForPotentialOrgs) = db.readWrite { implicit session =>
      val ignoreForPotentialOrgs = userValueRepo.getValue(event.userId, UserValues.ignoreForPotentialOrganizations)
      val currentCluster = userIpAddressRepo.getUsersFromIpAddressSince(event.ip, now.minus(clusterMemoryTime))

      if (!userIsFake) {
        val model = UserIpAddress(userId = event.userId, ipAddress = event.ip, agentType = agentType)
        val newIp = userIpAddressRepo.saveIfNew(model)
      }

      (currentCluster.toSet, ignoreForPotentialOrgs)
    }

    if (event.reportNewClusters
      && !cluster.contains(event.userId)
      && cluster.nonEmpty
      && !ignoreForPotentialOrgs
      && !userIsFake
      && !Set("67.161.4.140", "67.160.194.3").contains(event.ip.ip) //office ip(s) address
      && !Play.maybeApplication.forall(_.mode == Mode.Dev)) {
      log.info("[IPTRACK NOTIFY] Cluster " + cluster + " has new member " + event.userId)
      getIpInfoOpt(event.ip).map(_.foreach { ipInfo =>
        notifySlackChannelAboutCluster(ipInfo, cluster + event.userId, event.userId)
        registerUserLocationInUserValue(event.userId, ipInfo)
      })
    } else {
      registerUserLocationInUserValue(event.userId, event.ip)
    }
  }

  def getLastLocation(userId: Id[User]): Future[Option[RichIpAddress]] = db.readWrite { implicit s =>
    userValueRepo.getUserValue(userId, UserValueName.LAST_RECORDED_LOCATION) match {
      case Some(locationValue) =>
        Future.successful(RichIpAddress.format.reads(Json.parse(locationValue.value)).asOpt)
      case None =>
        userIpAddressRepo.getLastByUser(userId) match {
          case None => Future.successful(None)
          case Some(userIpAddress) =>
            val infoF = getIpInfoOpt(userIpAddress.ipAddress)
            infoF.map(_.foreach { ipInfo =>
              userValueRepo.setValue(userId, UserValueName.LAST_RECORDED_LOCATION, RichIpAddress.format.writes(ipInfo).toString())
            })
            infoF
        }
    }
  }

  def registerUserLocationInUserValue(userId: Id[User], ipInfo: RichIpAddress): Unit = {
    db.readWrite { implicit session =>
      def save() = userValueRepo.setValue(userId, UserValueName.LAST_RECORDED_LOCATION, RichIpAddress.format.writes(ipInfo).toString())
      userValueRepo.getUserValue(userId, UserValueName.LAST_RECORDED_LOCATION) match {
        case None => save()
        case Some(locationValue) =>
          RichIpAddress.format.reads(Json.parse(locationValue.value)).asOpt.foreach { lastIp =>
            if (lastIp.ip != ipInfo.ip) save()
          }
      }
    }
  }

  def registerUserLocationInUserValue(userId: Id[User], ip: IpAddress): Future[Option[RichIpAddress]] = {
    db.readWrite { implicit session =>
      def save(): Future[Option[RichIpAddress]] = {
        val infoF = getIpInfoOpt(ip)
        infoF.map(_.foreach { ipInfo =>
          userValueRepo.setValue(userId, UserValueName.LAST_RECORDED_LOCATION, RichIpAddress.format.writes(ipInfo).toString())
        })
        infoF
      }
      userValueRepo.getUserValue(userId, UserValueName.LAST_RECORDED_LOCATION) match {
        case None => save()
        case Some(locationValue) =>
          RichIpAddress.format.reads(Json.parse(locationValue.value)).asOpt.map { lastIp =>
            if (lastIp.ip != ip) save() else Future.successful(Some(lastIp))
          } getOrElse Future.successful(None)
      }
    }
  }

  def simplifyUserAgent(userAgent: UserAgent): String = {
    val agentType = userAgent.typeName.toUpperCase()
    if (agentType.isEmpty) "NONE" else agentType
  }

  private def linkToOrg(flag: String = "")(org: Organization): DescriptionElements = {
    import DescriptionElements._
    fromText(s"${org.name}$flag") --> LinkElement(s"https://admin.kifi.com/admin/organization/${org.id.get.id}")
  }

  private def formatUser(user: User, email: Option[EmailAddress], candOrgs: Seq[Organization], orgs: Seq[Organization], newMember: Boolean = false) = {
    import DescriptionElements._
    val primaryMail = email.map(_.address).getOrElse("No Primary Mail")
    DescriptionElements(
      user.fullName --> LinkElement(s"http://admin.kifi.com/admin/user/${user.id.get}"),
      primaryMail,
      s"joined ${STANDARD_DATE_FORMAT.print(user.createdAt)}",
      if (orgs.nonEmpty) DescriptionElements("\torgs ", orgs.map(linkToOrg(""))) else None,
      if (candOrgs.nonEmpty) DescriptionElements("\tcands ", candOrgs.map(linkToOrg("~"))) else None,
      if (newMember) DescriptionElements("*NEW CLUSTER MEMBER*") else None
    )
  }

  private val GenericISP = Seq("comcast", "at&t", "verizon", "level 3", "communication", "mobile", "internet", "wifi", "broadband", "telecom", "orange", "network").map(_.toLowerCase)
  private def isGenericISP(org: String) = GenericISP.exists(isp => org.toLowerCase.contains(isp))

  private def describeCluster(ip: RichIpAddress, users: Seq[(User, Option[EmailAddress], Seq[Organization], Seq[Organization])], newUserId: Id[User]): SlackMessageRequest = {
    import DescriptionElements._
    val clusterDeclaration = DescriptionElements(
      "Found a cluster of", users.length, "at", ip.ip.ip --> LinkElement("http://ip-api.com/${ip.ip.ip}"), ".",
      "I think the company is in", ip.region.map(_ + ", "), ip.country, ".", ip.org match {
        case Some(org) if isGenericISP(org) => "Generic ISP"
        case Some(maybeCompany) => s"ISP name is '$maybeCompany' (may be company name)"
        case None => "ISP name not found"
      }
    )

    val userDeclarations = users.map {
      case (user, email, candidateOrgs, orgs) => formatUser(user, email, candidateOrgs, orgs, user.id.get == newUserId)
    }

    SlackMessageRequest.inhouse(DescriptionElements.unlines(clusterDeclaration +: userDeclarations))
  }

  private def heuristicsSayThisClusterIsRelevant(ipInfo: RichIpAddress): Boolean = {
    !ipInfo.org.exists(company => UserIpAddressRules.blacklistCompanies.contains(company.toLowerCase))
  }

  def getIpInfoOpt(ip: IpAddress): Future[Option[RichIpAddress]] = richIpAddressCache.getOrElseFutureOpt(RichIpAddressKey(ip)) {
    val resF = httpClient.getFuture(DirectUrl(s"http://pro.ip-api.com/json/${ip.ip}?key=mnU7wRVZAx6BAyP")).map(_.json.asOpt[JsObject])
    resF.map { jsonOpt =>
      jsonOpt.map(RichIpAddress(ip, _))
    }
  }

  def shouldIgnoreAll(clusterUsers: Seq[(User, Option[EmailAddress], Seq[Organization], Seq[Organization])]): Boolean =
    db.readOnlyReplica { implicit s =>
      clusterUsers.forall {
        case (user, email, orgs, cands) =>
          orgs.nonEmpty || cands.nonEmpty || userValueRepo.getValue(user.id.get, UserValues.ignoreForPotentialOrganizations)
      }
    }

  def notifySlackChannelAboutCluster(ipInfo: RichIpAddress, clusterMembers: Set[Id[User]], newUserId: Id[User]): Unit = {
    val usersFromCluster = db.readOnlyMaster { implicit session =>
      val userIds = clusterMembers.toSeq
      userRepo.getUsers(userIds).values.toList map { user =>
        val candidates = organizationRepo.getByIds(organizationMembershipCandidateRepo.getAllByUserId(user.id.get).map(_.organizationId).toSet).values.toList
        val orgs = organizationRepo.getByIds(organizationMembershipRepo.getAllByUserId(user.id.get).map(_.organizationId).toSet).values.toList
        val email = Try(emailRepo.getByUser(user.id.get)).toOption
        (user, email, candidates, orgs)
      }
    }
    if (heuristicsSayThisClusterIsRelevant(ipInfo)) {
      if (shouldIgnoreAll(usersFromCluster)) {
        log.info(s"[IPTRACK NOTIFY] Decided not to notify about ${ipInfo.ip} since all users are members or " +
          s"candidates of organizations or have been marked as ignored for potential organizations")
      } else {
        log.info(s"[IPTRACK NOTIFY] making request to notify slack channel about ${ipInfo.ip}")
        val msg = describeCluster(ipInfo, usersFromCluster, newUserId)
        slackClient.sendToSlack(InhouseSlackChannel.IP_CLUSTERS, msg)
      }
    }
  }

  private def heuristicsSayThisClusterIsRelevant(ipInfo: Option[JsObject]): Boolean = {
    val companyOpt = ipInfo flatMap { obj => (obj \ "org").asOpt[String] }
    !companyOpt.exists(company => UserIpAddressRules.blacklistCompanies.contains(company.toLowerCase))
  }

  def totalNumberOfLogs(): Int = {
    db.readOnlyReplica { implicit session => userIpAddressRepo.count }
  }

}

class UserIpAddressCommander @Inject() (
    db: Database,
    airbrake: AirbrakeNotifier,
    userIpAddressRepo: UserIpAddressRepo,
    actor: ActorInstance[UserIpAddressActor]) extends Logging {

  def logUserByRequest[T](request: UserRequest[T]): Unit = {
    //not feeling like it...

    //    val userId = request.userId
    //    val userAgent = UserAgent(request.headers.get("user-agent").getOrElse(""))
    //    val ip = IpAddress.fromRequest(request)
    //    try {
    //      actor.ref ! UserIpAddressEvent(userId, ip, userAgent)
    //    } catch {
    //      case e: Exception =>
    //        airbrake.notify(s"error logging user $userId with headers: ${request.headers.toMap.mkString(", ")}", e)
    //    }
  }

  def countByUser(userId: Id[User]): Int = {
    db.readOnlyReplica { implicit session => userIpAddressRepo.countByUser(userId) }
  }
  def getByUser(userId: Id[User], limit: Int): Seq[UserIpAddress] = {
    db.readOnlyReplica { implicit session => userIpAddressRepo.getByUser(userId, limit) }
  }

  def getUsersByIpAddressSince(ip: IpAddress, time: DateTime): Seq[Id[User]] = {
    db.readOnlyReplica { implicit session => userIpAddressRepo.getUsersFromIpAddressSince(ip, time) }
  }

  def kvPairsToMap[A, B](kvs: Seq[(A, B)]): Map[A, Seq[B]] = {
    kvs.groupBy(_._1).mapValues(_.map(_._2))
  }
  def findSharedIpsByUser(userId: Id[User], limit: Int): Map[IpAddress, Seq[Id[User]]] = {
    val sharedIps = db.readOnlyReplica { implicit session =>
      userIpAddressRepo.findSharedIpsByUser(userId, limit)
    }
    kvPairsToMap(sharedIps)
  }
  def findIpClustersSince(time: DateTime, limit: Int): Seq[IpAddress] = {
    db.readOnlyReplica { implicit session =>
      userIpAddressRepo.findIpClustersSince(time, limit)
    }
  }
}
