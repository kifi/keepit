package com.keepit.common.amazon

import com.keepit.common.time._
import com.google.inject.{Inject, Singleton}
import com.keepit.common.net.HttpClient
import com.keepit.common.service.IpAddress

case class AmazonInstanceId(id: String) extends AnyVal {
  override def toString(): String = id
}

@Singleton
class AmazonInstanceInfo @Inject() (httpClient: HttpClient) {
  private val metadataUrl: String = "http://169.254.169.254/latest/meta-data/"
  lazy val instanceId: AmazonInstanceId = AmazonInstanceId(httpClient.get(metadataUrl + "instance-id").body)
  lazy val localHostname: String = httpClient.get(metadataUrl + "local-hostname").body
  lazy val publicHostname: String = httpClient.get(metadataUrl + "public-hostname").body
  lazy val localIp: IpAddress = IpAddress(httpClient.get(metadataUrl + "local-ipv4").body)
  lazy val publicIp: IpAddress = IpAddress(httpClient.get(metadataUrl + "public-ipv4").body)
  lazy val instanceType: String = httpClient.get(metadataUrl + "instance-type").body
  lazy val availabilityZone: String = httpClient.get(metadataUrl + "placement/availability-zone").body
  lazy val securityGroups: String = httpClient.get(metadataUrl + "security-groups").body
  lazy val amiId: String = httpClient.get(metadataUrl + "ami-id").body
  lazy val amiLaunchIndex: String = httpClient.get(metadataUrl + "ami-launch-index").body
}
