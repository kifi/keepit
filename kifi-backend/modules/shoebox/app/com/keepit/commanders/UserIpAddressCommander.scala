package com.keepit.commanders

import com.google.inject.Inject
import com.keepit.common.actor.ActorInstance
import com.keepit.common.akka.{ UnsupportedActorMessage, FortyTwoActor, SafeFuture }
import com.keepit.common.cache.{ ImmutableJsonCacheImpl, FortyTwoCachePlugin, CacheStatistics, Key }
import com.keepit.common.controller.UserRequest
import com.keepit.common.db.{ SequenceNumber, State, ExternalId, Id }
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.{ AccessLog, Logging }
import com.keepit.common.cache.TransactionalCaching.Implicits._
import com.keepit.common.net.{ DirectUrl, HttpClient, UserAgent }
import com.keepit.common.service.IpAddress
import com.keepit.common.time._
import com.keepit.model._
import org.joda.time.{ DateTime, Period }
import play.api.libs.functional.syntax._
import play.api.libs.json._
import com.kifi.macros.json

import scala.concurrent.duration.Duration
import scala.concurrent.{ ExecutionContext, Future }

case class RichIpAddress(ip: IpAddress, org: Option[String], country: Option[String], region: Option[String], city: Option[String],
  lat: Option[Double], lon: Option[Double], timezone: Option[String], zip: Option[String])

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
    "San Francisco International Airport", "Nomad Digital", "Choopa, LLC", "Linode").map(_.toLowerCase)
}

case class UserIpAddressEvent(userId: Id[User], ip: IpAddress, userAgent: UserAgent, reportNewClusters: Boolean = true)

class UserIpAddressActor @Inject() (userIpAddressEventLogger: UserIpAddressEventLogger, airbrake: AirbrakeNotifier) extends FortyTwoActor(airbrake) {

  def receive = {
    case event: UserIpAddressEvent => userIpAddressEventLogger.logUser(event)
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
    userIpAddressRepo: UserIpAddressRepo,
    httpClient: HttpClient,
    userStatisticsCommander: UserStatisticsCommander,
    richIpAddressCache: RichIpAddressCache,
    implicit val executionContext: ExecutionContext,
    clock: Clock) extends Logging {

  private val ipClusterSlackChannelUrl = "https://hooks.slack.com/services/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"
  private val clusterMemoryTime = Period.weeks(10) // How long back do we look and still consider a user to be part of a cluster

  def logUser(event: UserIpAddressEvent): Unit = {
    if (event.ip.ip.toString.startsWith("10.")) {
      throw new IllegalArgumentException(s"IP Addresses of the form 10.x.x.x are internal ec2 addresses and should not be logged. User ${event.userId}, ip ${event.ip}, agent ${event.userAgent}")
    }
    val now = clock.now()
    val agentType = simplifyUserAgent(event.userAgent)
    if (agentType == "NONE") {
      log.info("[IPTRACK AGENT] Could not parse an agent type out of: " + event.userAgent)
    }
    val model = UserIpAddress(userId = event.userId, ipAddress = event.ip, agentType = agentType)

    val cluster = db.readWrite { implicit session =>
      val currentCluster = userIpAddressRepo.getUsersFromIpAddressSince(event.ip, now.minus(clusterMemoryTime))
      userIpAddressRepo.saveIfNew(model)
      currentCluster.toSet
    }

    if (event.reportNewClusters && !cluster.contains(event.userId) && cluster.size >= 1) {
      log.info("[IPTRACK NOTIFY] Cluster " + cluster + " has new member " + event.userId)
      notifySlackChannelAboutCluster(clusterIp = event.ip, clusterMembers = cluster + event.userId, newUserId = Some(event.userId))
    }
  }

  def simplifyUserAgent(userAgent: UserAgent): String = {
    val agentType = userAgent.typeName.toUpperCase()
    if (agentType.isEmpty) "NONE" else agentType
  }

  private def formatCluster(ip: RichIpAddress, users: Seq[UserStatistics], newUserId: Option[Id[User]]): BasicSlackMessage = {
    val clusterDeclaration = Seq(
      s"Found a cluster of ${users.length} at <http://ip-api.com/${ip.ip.ip}|${ip.ip.ip}>",
      s"I think the company is in ${ip.region.map(_ + ", ").getOrElse("")}${ip.country.getOrElse("")} ",
      ip.org.map(org => s"I think the company is '$org'").getOrElse("no company found")
    )

    val userDeclarations = users.map { stats =>
      val user = stats.user
      val primaryMail = user.primaryEmail.map(_.address).getOrElse("No Primary Mail")
      val userDeclaration = s"<http://admin.kifi.com/admin/user/${user.id.get}|${user.fullName}>\t$primaryMail\tjoined ${STANDARD_DATE_FORMAT.print(user.createdAt)}\t${stats.connections} conns\t${stats.librariesCreated}/${stats.librariesFollowed} lib cr/fw\t${stats.publicKeeps}/${stats.privateKeeps} pb/pv keeps\t"
      if (newUserId.contains(user.id.get)) {
        userDeclaration + "\t*<-- New Member in Cluster!!!*"
      } else userDeclaration
    }

    BasicSlackMessage((clusterDeclaration ++ userDeclarations).mkString("\n"))
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

  def notifySlackChannelAboutCluster(clusterIp: IpAddress, clusterMembers: Set[Id[User]], newUserId: Option[Id[User]] = None): Unit = {
    log.info("[IPTRACK NOTIFY] Notifying slack channel about " + clusterIp)
    val usersFromCluster = db.readOnlyMaster { implicit session =>
      val userIds = clusterMembers.toSeq
      userRepo.getUsers(userIds).values.toList.map { user =>
        userStatisticsCommander.userStatistics(user, Map.empty)
      }
    }
    getIpInfoOpt(clusterIp) map { ipInfoOpt =>
      log.info("[IPTRACK NOTIFY] Retrieved IP geolocation info: " + ipInfoOpt)

      ipInfoOpt foreach { ipInfo =>
        if (heuristicsSayThisClusterIsRelevant(ipInfo)) {
          val msg = formatCluster(ipInfo, usersFromCluster, newUserId)
          httpClient.post(DirectUrl(ipClusterSlackChannelUrl), Json.toJson(msg))
        }
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
    userIpAddressRepo: UserIpAddressRepo,
    actor: ActorInstance[UserIpAddressActor]) extends Logging {

  def logUser(userId: Id[User], ip: IpAddress, userAgent: UserAgent, reportNewClusters: Boolean = true): Unit = {
    actor.ref ! UserIpAddressEvent(userId, ip, userAgent, reportNewClusters)
  }

  def logUserByRequest[T](request: UserRequest[T]): Unit = {
    val userId = request.userId
    val userAgent = UserAgent(request.headers.get("user-agent").getOrElse(""))
    val raw_ip_string = request.headers.get("X-Forwarded-For").getOrElse(request.remoteAddress)
    val ip = IpAddress(raw_ip_string.split(",").head)
    actor.ref ! UserIpAddressEvent(userId, ip, userAgent)
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
